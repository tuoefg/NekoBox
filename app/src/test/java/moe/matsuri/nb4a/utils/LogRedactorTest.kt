package moe.matsuri.nb4a.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The exported log must lose its credentials; every pattern must also survive Android's ICU engine. */
class LogRedactorTest {

    private val redactor = LogRedactor(hideDestinations = false)

    @Test
    fun jsonSecretsAreRedacted() {
        val out = redactor.redact(
            """config: {"server": "example.com:443", "password": "secret123", "uuid": "550e8400-e29b-41d4-a716-446655440000"}"""
        )
        assertFalse("password leaked: $out", out.contains("secret123"))
        assertFalse("uuid leaked: $out", out.contains("550e8400-e29b-41d4-a716-446655440000"))
        assertFalse("host leaked: $out", out.contains("example.com"))
        assertTrue("shape lost: $out", out.contains("config: "))
    }

    @Test
    fun shareLinkCredentialsAndAddressAreRedacted() {
        val out = redactor.redact("ss://YWJjZGVmZ2hpamtsbW5vcA==@1.2.3.4:8388#node")
        assertFalse("key leaked: $out", out.contains("YWJjZGVmZ2hpamtsbW5vcA=="))
        assertFalse("public ip leaked: $out", out.contains("1.2.3.4"))
        assertTrue("scheme lost: $out", out.startsWith("ss://"))
    }

    @Test
    fun wifiNamesAreRedacted() {
        val out = redactor.redact("SSID=MyHomeWifi, BSSID=aa:bb:cc:dd:ee:ff")
        assertFalse("ssid leaked: $out", out.contains("MyHomeWifi"))
        assertFalse("bssid leaked: $out", out.contains("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun aPlainLineSurvivesUnchanged() {
        val line = "20:31:02 [info] inbound/mixed[mixed-in]: started at mixed-in[::]:7890"
        assertEquals(line, redactor.redact(line))
    }

    @Test
    fun hideDestinationsRedactsDomainNames() {
        val line = "connection to api.example.org:443"
        val hidden = LogRedactor(hideDestinations = true).redact(line)
        assertFalse("domain leaked: $hidden", hidden.contains("api.example.org"))
        // With the flag off the domain of a non-connection line stays: the flag is opt-in.
        assertTrue(redactor.redact(line).contains("api.example.org"))
    }

    /**
     * Android's java.util.regex is ICU underneath and rejected the nested possessive quantifiers of the
     * 2026-11 release's SECRET pattern at class-initialization time, crashing every log export and the
     * crash screen itself. The JVM's engine accepts them, so a green JVM suite proves nothing there —
     * this guard keeps possessive quantifiers out of the patterns altogether (they buy nothing on log
     * lines, and ICU has repeatedly rejected the nested forms).
     */
    @Test
    fun thePatternsAvoidPossessiveQuantifiers() {
        val source = File("src/main/java/moe/matsuri/nb4a/utils/LogRedactor.kt")
        assertTrue("source not found: ${source.absolutePath}", source.exists())
        val possessive = Regex("""(?:[*+?]|\{\d+(?:,\d*)?\})\+""")
        val offenders = source.readLines().withIndex()
            .filter { (_, line) ->
                val code = line.trimStart()
                // Comments discuss C++ and the very construct this guards against.
                val inComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                !inComment && possessive.containsMatchIn(line)
            }
            .map { (index, line) -> "${index + 1}: ${line.trim()}" }
        assertTrue("possessive quantifiers are unsafe on Android's ICU engine:\n" + offenders.joinToString("\n"),
            offenders.isEmpty())
    }
}
