package io.throne.bbox.bridge

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.backup.BackupExport
import io.nekohasekai.sagernet.database.backup.BackupRestore
import io.nekohasekai.sagernet.database.backup.DesktopSchema
import io.nekohasekai.sagernet.database.backup.ThrBackup
import io.nekohasekai.sagernet.database.backup.WebDavBackup
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Backup and restore in three dialects: the ported UI's JSON envelope, Throne's own container, and WebDAV.
 *
 * The screens deal in JSON — `backup.create` hands back a map the Dart side encodes and writes wherever it likes — but
 * Throne's own backup is a binary container whose import rules (id counters, table repair, setting merges) are real
 * and easy to get wrong. So the map is an envelope: its `thr` member is the genuine Throne backup, and restoring
 * decodes it straight into Throne's restore rather than reimplementing any of that.
 *
 * The `profiles` member is the part the UI reads, so it carries the group names and their node counts — enough for the
 * confirmation dialog to say what is about to replace what.
 */
object BboxBackup {

    private const val KEY = "thr"

    /** A backup whose real content is Throne's own, held until it is restored. */
    private class Holder(val payload: Map<String, Any?>, val loaded: BackupRestore.Loaded? = null)

    @Volatile
    private var holder: Holder? = null

    @Volatile
    private var lastPayload: Map<String, Any?>? = null

    private fun selection(scope: String): BackupExport.Selection {
        val everything = scope != "profiles"
        return BackupExport.Selection(profiles = true, routes = everything, settings = everything)
    }

    /** Builds a Throne backup and wraps it in the envelope the UI expects. */
    suspend fun create(scope: String = "all"): Map<String, Any?> = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream().use { out ->
            BackupExport.write(selection(scope), out)
            out.toByteArray()
        }
        val groups = GroupRepo.all().map { group ->
            mapOf(
                "name" to group.displayName(),
                "type" to if (group.isSubscription) "subscription" else "local",
                "url" to group.url,
                "nodes" to ProfileManager.memberIds(group.id).size,
            )
        }
        val payload = mapOf(
            "format" to ThrBackup.FORMAT_VERSION,
            "createdAt" to System.currentTimeMillis(),
            "scope" to scope,
            "profiles" to groups,
            KEY to Base64.encodeToString(bytes, Base64.DEFAULT),
        )
        lastPayload = payload
        holder = Holder(payload)
        payload
    }

    /** A preview: whatever was created or read most recently, since there is no on-device backup registry to list. */
    fun read(): Map<String, Any?> = holder?.payload ?: lastPayload ?: mapOf(
        "profiles" to emptyList<Map<String, Any?>>(),
    )

    /**
     * A .thrbackup the platform handed us (a VIEW or SEND of a "THRN" container). It becomes the same envelope
     * [create] and [readWebdav] hand out, so the confirmation and [restore] need no path of their own.
     *
     * Returns null when [bytes] are not a Throne backup at all — a shared document is the importer's business —
     * and refuses a container it cannot read, which only the magic can tell apart.
     */
    suspend fun open(bytes: ByteArray): Map<String, Any?>? = withContext(Dispatchers.IO) {
        // "THRN" first and silently: a plain document is not a broken backup.
        if (!ThrBackup.hasMagic(ByteArrayInputStream(bytes))) return@withContext null
        val loaded = runCatching { BackupRestore.load(ByteArrayInputStream(bytes)) }
            .onFailure { Logs.w(it) }.getOrNull() ?: error("This backup cannot be read")
        val payload = mapOf(
            "format" to loaded.contents.formatVersion,
            "createdAt" to loaded.contents.createdAt,
            "profiles" to profileNames(loaded.database),
            KEY to Base64.encodeToString(bytes, Base64.DEFAULT),
        )
        // The envelope is what travels to the UI and what [restore] decodes, so the staged database goes back.
        loaded.discard()
        holder = Holder(payload)
        payload
    }

    /**
     * Restores an envelope. [strategy] is B-box's merge-vs-replace choice, which Throne's restore does not have — it
     * replaces a part's rows keeping ids, so `Compatible` narrows the selection to profiles rather than pretending to
     * merge. `Override` additionally takes the routes and settings.
     */
    suspend fun restore(payload: Map<String, Any?>?, scope: String, strategy: String): List<String> =
        withContext(Dispatchers.IO) {
            val current = holder
            val loaded = when {
                // The newest remote read is only reachable through the handle that download gave us.
                payload != null && current?.payload === payload && current.loaded != null -> current.loaded
                else -> decode(payload)?.let { BackupRestore.load(it) } ?: current?.loaded
                    ?: error("Nothing has been read yet: choose a backup to restore from")
            }
            val available = BackupRestore.available(loaded.contents)
            val everything = strategy == "Override" && scope != "profiles"
            val choice = BackupRestore.Choice(
                profiles = available.profiles,
                routes = available.routes && everything,
                settings = available.settings && everything,
            )
            if (!choice.any()) error("This backup holds nothing Throne can restore")
            val warnings = try {
                BackupRestore.restore(loaded, choice)
            } finally {
                holder = null
            }
            BboxBridge.scheduleRefresh(immediate = true)
            warnings
        }

    private fun decode(payload: Map<String, Any?>?): ByteArrayInputStream? {
        val encoded = payload?.get(KEY) as? String ?: return null
        return ByteArrayInputStream(Base64.decode(encoded, Base64.DEFAULT))
    }

    // -------------------------------------------------------------------------------------------------- WebDAV

    /**
     * The bound WebDAV account. Throne stores it as loose settings rather than one record, and its own settings screen
     * is the only thing that reads them today; this is the same four keys.
     */
    fun webdav(): Map<String, Any?>? {
        val server = DataStore.webdavServer.orEmpty()
        if (server.isBlank()) return null
        return mapOf(
            "uri" to server,
            "user" to DataStore.webdavUsername.orEmpty(),
            "password" to DataStore.webdavPassword.orEmpty(),
            "fileName" to DataStore.webdavPath.orEmpty(),
        )
    }

    /** Binds, rebinds (when [value] is a map) or unbinds (when it is null) the account. */
    fun setWebdav(value: Map<String, Any?>?): Map<String, Any?>? {
        if (value == null) {
            DataStore.webdavServer = ""
            DataStore.webdavUsername = ""
            DataStore.webdavPassword = ""
            DataStore.webdavPath = ""
            return null
        }
        DataStore.webdavServer = value["uri"] as? String ?: ""
        DataStore.webdavUsername = value["user"] as? String ?: ""
        DataStore.webdavPassword = value["password"] as? String ?: ""
        DataStore.webdavPath = value["fileName"] as? String ?: ""
        return webdav()
    }

    suspend fun checkWebdav(): Boolean = withContext(Dispatchers.IO) {
        val configured = WebDavBackup.configured() ?: return@withContext false
        runCatching { configured.check(); true }.onFailure { Logs.w(it) }.getOrDefault(false)
    }

    /** Uploads a fresh backup, ignoring [scope] beyond what it decides to include. */
    suspend fun uploadWebdav(scope: String = "all"): Boolean = withContext(Dispatchers.IO) {
        val configured = WebDavBackup.configured() ?: return@withContext false
        val parts = selection(scope).parts()
        val file = runCatching { File.createTempFile("thrbackup-", ".container") }.getOrNull() ?: return@withContext false
        try {
            file.outputStream().use { out -> BackupExport.write(selection(scope), out) }
            runCatching { configured.upload(file, parts); true }.onFailure { Logs.w(it) }.getOrDefault(false)
        } finally {
            runCatching { file.delete() }
        }
    }

    /** Reads the newest remote backup, keeping its container so the following restore has something to restore. */
    suspend fun readWebdav(): Map<String, Any?>? = withContext(Dispatchers.IO) {
        val configured = WebDavBackup.configured() ?: return@withContext null
        val entries = runCatching { configured.list() }.onFailure { Logs.w(it) }.getOrNull().orEmpty()
        val newest = entries.maxByOrNull { it.createdAt } ?: return@withContext mapOf(
            "profiles" to emptyList<Map<String, Any?>>(),
        )
        val loaded = runCatching { configured.download(newest) }.onFailure { Logs.w(it) }.getOrNull()
            ?: return@withContext null
        val payload = mapOf(
            "format" to loaded.contents.formatVersion,
            "createdAt" to loaded.contents.createdAt,
            "profiles" to profileNames(loaded.database),
        )
        holder = Holder(payload, loaded)
        payload
    }

    /** Group names and node counts straight out of a staged backup database, for the restore confirmation. */
    private fun profileNames(database: File): List<Map<String, Any?>> = runCatching {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            if (!db.hasTable(DesktopSchema.PROFILES)) return emptyList()
            val nodeCounts = if (db.hasTable(DesktopSchema.ENTITY_IDS)) emptyMap<Long, Long>() else emptyMap()
            db.rawQuery("SELECT id, name FROM ${DesktopSchema.PROFILES}", null).use { cursor ->
                val rows = ArrayList<Map<String, Any?>>()
                while (cursor.moveToNext()) {
                    rows += mapOf(
                        "name" to cursor.getString(1),
                        "nodes" to (nodeCounts[cursor.getLong(0)] ?: 0L),
                    )
                }
                rows
            }
        }
    }.onFailure { Logs.w(it) }.getOrDefault(emptyList())

    private fun SQLiteDatabase.hasTable(name: String): Boolean = runCatching {
        DatabaseUtils.longForQuery(this, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)) > 0
    }.getOrDefault(false)

    private fun BackupExport.Selection.parts() = ThrBackup.Parts(profiles = profiles, routes = routes, settings = settings)
}
