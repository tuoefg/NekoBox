package moe.matsuri.nb4a.utils

import java.util.regex.Matcher

/**
 * Best-effort redaction of exported log lines: URL paths/queries and credentials, secret and server-naming key/value
 * pairs, UUIDs, base64 keys, public IP addresses and, with [hideDestinations], domain names in connection and DNS lines.
 *
 * Every pattern compiles through [safeRegex] and every pass runs under [runCatching]: Android's ICU regex engine has
 * rejected constructs the desktop JVM accepts (the nested possessive quantifiers of the old SECRET pattern threw
 * PatternSyntaxException in `<clinit>`, which turned every log export into a crash loop — LogExport.build touches this
 * class from BlankActivity). A rule that fails to compile or apply degrades to "leave the line as is"; a broken rule
 * must never take the export, or the app, down.
 */
class LogRedactor(private val hideDestinations: Boolean) {

    private class Rule(val regex: Regex, val transform: Match.() -> CharSequence)

    private val rules: List<Rule> = buildList {
        URL?.let { add(Rule(it) {
            val (_, scheme, credentials, host, rest) = groupValues
            "$scheme://" + (if (credentials.isNotEmpty()) "<redacted>@" else "") + host +
                    (if (rest.isNotEmpty()) "/<redacted>" else "")
        }) }
        SECRET?.let { add(Rule(it) { redactValues(this) }) }
        SERVER?.let { add(Rule(it) { redactValues(this) }) }
        WIFI_LIST?.let { add(Rule(it) { groupValues[1] + "[\"<redacted>\"]" }) }
        WIFI?.let { add(Rule(it) { groupValues[1] + groupValues[2] + "<redacted>" }) }
        UUID?.let { add(Rule(it) { "<uuid>" }) }
        BASE64?.let { add(Rule(it) {
            val v = value
            if (v.any(Char::isUpperCase) && v.any(Char::isLowerCase) && v.any(Char::isDigit)) "<key>" else v
        }) }
        IPV4?.let { add(Rule(it) {
            val octets = groupValues.drop(1).map { it.toInt() }
            if (octets.any { it > 255 } || isPrivate4(octets)) value else "<ip>"
        }) }
        IPV6?.let { add(Rule(it) {
            val v = value
            if (!NGUtil.isIpv6Address(v) || isPrivate6(v.lowercase())) v else "<ip>"
        }) }
        if (hideDestinations) DOMAIN?.let { add(Rule(it) {
            "<domain>.${groupValues[1]}"
        }) }
    }

    fun redact(line: String): String {
        var s = line
        for (rule in rules) {
            s = runCatching { rule.regex.replaceEach(s) { rule.transform(it) } }.getOrDefault(s)
        }
        return s
    }

    private fun redactValues(m: Match): String {
        val (_, quote, key, separator, value) = m.groupValues
        val redacted = when (value.firstOrNull()) {
            '"' -> "\"<redacted>\""
            '[' -> "[\"<redacted>\"]"
            '{' -> "{<redacted>}"
            else -> "<redacted>"
        }
        return "$quote$key$quote$separator$redacted"
    }

    private fun isPrivate4(o: List<Int>): Boolean = when {
        o[0] == 10 || o[0] == 127 || o[0] == 0 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 169 && o[1] == 254 -> true
        o[0] == 100 && o[1] in 64..127 -> true
        o[0] >= 224 -> true
        else -> false
    }

    private fun isPrivate6(v: String): Boolean =
        v == "::" || v == "::1" || v.startsWith("fe8") || v.startsWith("fe9") || v.startsWith("fea") ||
                v.startsWith("fc") || v.startsWith("fd") || v.startsWith("ff")

    /** The [MatchResult] accessors the transforms use, read off the one Matcher of a [replaceEach] pass. */
    private class Match(private val matcher: Matcher) {
        val value: String get() = matcher.group()
        val groupValues: List<String> get() = List(matcher.groupCount() + 1) { matcher.group(it) ?: "" }
    }

    /**
     * [Regex.replace] with a transform, on one Matcher: Kotlin's makes a new Matcher per match, and each one copies the
     * whole input (the logged start config is a single line of up to megabytes).
     */
    private fun Regex.replaceEach(input: String, transform: (Match) -> CharSequence): String {
        val matcher = toPattern().matcher(input)
        if (!matcher.find()) return input
        val match = Match(matcher)
        val out = StringBuilder(input.length)
        var last = 0
        do {
            out.append(input, last, matcher.start()).append(transform(match))
            last = matcher.end()
        } while (last < input.length && matcher.find())
        return out.append(input, last, input.length).toString()
    }

    companion object {

        /**
         * Android's java.util.regex is a JNI wrapper around ICU's C++ engine, which rejects constructs the desktop
         * JVM compiles — the old nested-possessive SECRET pattern threw PatternSyntaxException on device while every
         * JVM test stayed green. Compile each pattern defensively; a rule that will not compile is skipped.
         */
        private fun safeRegex(pattern: String): Regex? = runCatching { Regex(pattern) }.getOrNull()

        // Flat (non-nested) JSON value matchers: strings, one bracket or brace level, quotes inside included.
        // The possessive/nested forms they replace are rejected by ICU on some API levels.
        private const val STRING = "\"(?:[^\"\\\\]|\\\\.)*\""
        private const val ARRAY = "\\[(?:[^\\[\\]]|\"(?:[^\"\\\\]|\\\\.)*\")*\\]"
        private const val OBJECT = "\\{(?:[^{}]|\"(?:[^\"\\\\]|\\\\.)*\")*\\}"

        private val URL = safeRegex("""\b([a-zA-Z][a-zA-Z0-9+.\-]*)://([^\s/@"'<>]*@)?([^\s/?#"'<>]+)([^\s"'<>]*)""")
        private val SECRET = safeRegex(
            """(?i)("?)([\w\-]*(?:password|passwd|pass|secret|token|key|uuid|auth|psk|short_id)[\w\-]*)\1(\s*[:=]\s*)($STRING|$ARRAY|$OBJECT|[^\s,;&}\]]+)"""
        )
        // The logged start config names the servers by domain as well as by IP.
        private val SERVER = safeRegex(
            """(?i)("?)(server|server_name|sni|host|address|path|service_name)\1(\s*[:=]\s*)("(?:[^"\\]|\\.)*"|[^\s,;&}\]]+)"""
        )
        // The core logs the connected Wi-Fi ("ssid NAME", "SSID=NAME, BSSID=…"); route rules list them as arrays.
        private val WIFI = safeRegex("""(?i)\b(b?ssid)(\s*[:=]\s*|\s+)("(?:[^"\\]|\\.)*"|[^\s,;]+)""")
        private val WIFI_LIST = safeRegex("""(?i)("wifi_b?ssid"\s*:\s*)\[[^\]]*]""")
        private val UUID = safeRegex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
        private val BASE64 = safeRegex("""(?<![A-Za-z0-9+/_\-])[A-Za-z0-9+/_\-]{32,}={0,2}(?![A-Za-z0-9+/=_\-])""")
        private val IPV4 = safeRegex("""(?<![\d.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![\d.])""")
        private val IPV6 = safeRegex("""(?<![0-9A-Za-z:])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?![0-9A-Za-z:])""")
        private val DESTINATION_LINE = safeRegex("""(?i)connection (?:to|from)|domain|dns|exchang|lookup|resolv|sniff""")
        private val DOMAIN = safeRegex("""(?<![\w.\-])(?:[a-zA-Z0-9](?:[a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+([a-z]{2,63})\.?(?![\w\-])""")
    }
}
