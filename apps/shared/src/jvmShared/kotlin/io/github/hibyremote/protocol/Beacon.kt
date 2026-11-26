package io.github.hibyremote.protocol

/** A player announcement received over UDP (PROTOCOL.md section 2.1). */
data class Beacon(val name: String, val host: String, val port: Int)

object Beacons {
    const val DEFAULT_PORT = 4001
    private const val SIZE = 334

    /** Parses a beacon sent by [sourceIp]; returns null if it is not one. */
    fun parse(data: ByteArray, length: Int, sourceIp: String): Beacon? {
        if (length >= SIZE && data[0] == 'H'.code.toByte() && data[1] == 'L'.code.toByte() &&
            data[2] == 'B'.code.toByte() && data[3] == 'M'.code.toByte()
        ) {
            val address = cString(data, 14, 64)
            val name = cString(data, 78, 256)
            return fromAddress(name, address, sourceIp)
        }
        val text = String(data, 0, length, Charsets.UTF_8)
        if (!text.trimStart().startsWith("{")) return null
        val name = jsonString(text, "name").orEmpty().ifEmpty { jsonString(text, "deviceName").orEmpty() }
        return fromAddress(name, jsonString(text, "address").orEmpty(), sourceIp)
    }

    /**
     * Extracts the top-level-looking string value of [key] from a flat JSON
     * object (a tiny scanner instead of org.json, which only exists on Android).
     * Returns null when the key is absent or its value is not a string.
     */
    internal fun jsonString(json: String, key: String): String? {
        var i = 0
        while (i < json.length) {
            if (json[i] != '"') {
                i++
                continue
            }
            val (token, end) = readString(json, i) ?: return null
            i = end
            var j = i
            while (j < json.length && json[j].isWhitespace()) j++
            if (token != key || j >= json.length || json[j] != ':') continue
            j++
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length || json[j] != '"') return null
            return readString(json, j)?.first
        }
        return null
    }

    /** Reads the JSON string literal starting at [start] (a quote); returns its value and the index after it. */
    private fun readString(json: String, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = start + 1
        while (i < json.length) {
            val c = json[i]
            when {
                c == '"' -> return sb.toString() to i + 1
                c == '\\' && i + 1 < json.length -> {
                    val n = json[i + 1]
                    i++
                    when (n) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = json.substring(i + 1, minOf(i + 5, json.length))
                            val cp = hex.toIntOrNull(16) ?: return null
                            if (hex.length < 4) return null
                            sb.append(cp.toChar())
                            i += 4
                        }
                        else -> sb.append(n)
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return null
    }

    private fun fromAddress(name: String, address: String, sourceIp: String): Beacon {
        val host = address.substringBefore(':').ifBlank { sourceIp }
        val port = address.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT
        return Beacon(name.ifBlank { host }, host, port)
    }

    private fun cString(data: ByteArray, off: Int, max: Int): String {
        var end = off
        while (end < off + max && data[end] != 0.toByte()) end++
        return String(data, off, end - off, Charsets.UTF_8).trim()
    }
}
