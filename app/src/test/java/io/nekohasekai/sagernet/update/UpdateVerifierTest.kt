package io.nekohasekai.sagernet.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The integrity gate every downloaded update must pass before the installer sees it. */
class UpdateVerifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var serial = 0

    private fun write(content: ByteArray): File =
        temp.newFile("update-${serial++}.apk").apply { writeBytes(content) }

    @Test
    fun sha256MatchesTheKnownVector() {
        // sha256 of an empty input.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UpdateVerifier.sha256(write(ByteArray(0))))
        // sha256 of "abc".
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateVerifier.sha256(write("abc".toByteArray())),
        )
    }

    @Test
    fun aCorrectDownloadPassesIntegrity() {
        val content = "apk bytes".toByteArray()
        val file = write(content)
        val sha = UpdateVerifier.sha256(file)
        // The manifest's digest is lowercase hex, as UpdateChecker lowercases it on read.
        UpdateVerifier.verifyIntegrity(file, content.size.toLong(), sha.lowercase())
    }

    @Test
    fun aTruncatedDownloadIsRejectedOnSize() {
        val file = write("short".toByteArray())
        try {
            UpdateVerifier.verifyIntegrity(file, 12345L, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
            fail("size mismatch must reject")
        } catch (e: UpdateVerifier.IntegrityRejected) {
            assertEquals(UpdateVerifier.IntegrityReason.SIZE, e.reason)
        }
    }

    @Test
    fun aTamperedDownloadIsRejectedOnDigest() {
        val file = write("legit apk".toByteArray())
        val digestOfSomethingElse = UpdateVerifier.sha256(write("something else".toByteArray()))
        assertThrows(UpdateVerifier.IntegrityRejected::class.java) {
            UpdateVerifier.verifyIntegrity(file, file.length().toLong(), digestOfSomethingElse)
        }.let { assertEquals(UpdateVerifier.IntegrityReason.SHA256, it.reason) }
    }

    @Test
    fun anUppercaseManifestDigestIsAccepted() {
        val content = "payload".toByteArray()
        val file = write(content)
        UpdateVerifier.verifyIntegrity(file, content.size.toLong(), UpdateVerifier.sha256(file).uppercase())
    }
}
