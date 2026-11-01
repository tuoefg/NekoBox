package io.nekohasekai.sagernet.database.backup

import android.content.Intent
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs

// Entry for a .thrbackup opened from outside (VIEW/SEND intents).
object BackupEntry {

    private var pending: Uri? = null

    /**
     * True when [uri] starts with the "THRN" magic; then Tools › Backup opens with the restore dialog for it. The
     * magic is read on the calling thread.
     */
    fun open(activity: FragmentActivity, uri: Uri): Boolean {
        val backup = try {
            activity.contentResolver.openInputStream(uri)?.use { ThrBackup.hasMagic(it) } == true
        } catch (e: Exception) {
            Logs.w("open backup $uri", e)
            false
        }
        if (!backup) return false
        synchronized(this) { pending = uri }
        return true
    }

    /** The Uri [open] handed to the Tools screen, once. */
    fun takePending(): Uri? = synchronized(this) { pending.also { pending = null } }
}
