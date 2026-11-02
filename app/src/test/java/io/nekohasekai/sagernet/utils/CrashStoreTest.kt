package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The durable crash records: one file per crash, pruned on a seven-day window. */
class CrashStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val now = System.currentTimeMillis()

    @org.junit.After
    fun reset() {
        CrashStore.dir = null
        CrashStore.clock = { System.currentTimeMillis() }
    }

    private fun install() {
        CrashStore.dir = temp.root
        CrashStore.clock = { now }
    }

    @Test
    fun aRecordCarriesThreadAndStackAndVersion() {
        install()
        val file = CrashStore.record(Thread.currentThread(), IllegalStateException("boom"))!!
        val text = file.readText()
        assertTrue(text.contains("thread: ${Thread.currentThread().name}"))
        assertTrue(text.contains("boom"))
        assertTrue(text.contains("version: "))
        assertEquals(listOf(file.name), CrashStore.list().map { it.name })
    }

    @Test
    fun recordsSortNewestFirstByName() {
        install()
        val first = CrashStore.record(Thread.currentThread(), RuntimeException("one"))!!
        val second = CrashStore.record(Thread.currentThread(), RuntimeException("two"))!!
        assertEquals(listOf(second.name, first.name), CrashStore.list().map { it.name })
    }

    @Test
    fun theSerialIsPaddedSoNamesSortWithinOneSecond() {
        install()
        // Two records in the same second: the unpadded serials 9 and 10 would sort 9 > 10.
        assertTrue(CrashStore.record(Thread.currentThread(), RuntimeException("a"))!!.name.endsWith("-0001.log"))
        assertTrue(CrashStore.record(Thread.currentThread(), RuntimeException("b"))!!.name.endsWith("-0002.log"))
        val ninth = CrashStore.record(Thread.currentThread(), RuntimeException("i"))!!
        val tenth = CrashStore.record(Thread.currentThread(), RuntimeException("x"))!!
        assertTrue(ninth.name.endsWith("-0009.log"))
        assertTrue(tenth.name.endsWith("-0010.log"))
        assertEquals(listOf(tenth.name, ninth.name), CrashStore.list().map { it.name }.take(2))
    }

    @Test
    fun pruneKeepsTheWindowAndDropsTheRest() {
        install()
        // Names carry stamps in the same format and timezone CrashStore writes them;
        // generate them relative to the pinned clock so the test is date-independent.
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", java.util.Locale.US)
        val day = 24 * 60 * 60 * 1000L
        val fresh = File(temp.root, "crash-${stamp.format(java.util.Date(now - 1 * day))}-101.log")
        val stale = File(temp.root, "crash-${stamp.format(java.util.Date(now - 30 * day))}-102.log")
        fresh.writeText("fresh\n")
        stale.writeText("stale\n")
        CrashStore.prune()
        assertTrue(fresh.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun anUnparseableNameIsDroppedRatherThanKeptForever() {
        install()
        val garbage = File(temp.root, "crash-not-a-date.log")
        garbage.writeText("x\n")
        CrashStore.prune()
        assertFalse(garbage.exists())
    }

    @Test
    fun readReturnsTheContent() {
        install()
        val file = CrashStore.record(Thread.currentThread(), RuntimeException("readable"))!!
        assertTrue(CrashStore.read(file.name).contains("readable"))
    }

    @Test
    fun anEmptyDirectoryListsNothing() {
        install()
        assertEquals(0, CrashStore.list().size)
    }
}
