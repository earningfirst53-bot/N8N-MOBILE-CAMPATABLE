package com.manhuadirector.mobile

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

object PdfTextLite {
    fun extract(pdf: ByteArray): String {
        val all = String(pdf, StandardCharsets.ISO_8859_1)
        val out = StringBuilder()
        var pos = 0
        while (true) {
            val s = all.indexOf("stream", pos)
            if (s < 0) break
            val headerStart = maxOf(0, all.lastIndexOf("<<", s))
            val header = all.substring(headerStart, s)
            var dataStart = s + 6
            if (all.startsWith("\r\n", dataStart)) dataStart += 2
            else if (all.startsWith("\n", dataStart)) dataStart += 1
            val e = all.indexOf("endstream", dataStart)
            if (e < 0) break
            val raw = pdf.copyOfRange(dataStart, e)
            val decoded = if (header.contains("/FlateDecode")) {
                runCatching { InflaterInputStream(ByteArrayInputStream(raw)).readBytes() }.getOrDefault(raw)
            } else raw
            extractStrings(String(decoded, StandardCharsets.ISO_8859_1), out)
            pos = e + 9
        }
        return out.toString()
    }

    private fun extractStrings(stream: String, out: StringBuilder) {
        val pattern = Regex("\\((?:\\\\.|[^\\\\])*\\)")
        for (m in pattern.findAll(stream)) {
            val raw = m.value.substring(1, m.value.length - 1)
            val decoded = decodePdfString(raw)
            if (decoded.any { it.isLetterOrDigit() }) out.append(decoded).append('\n')
        }
    }

    private fun decodePdfString(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    '(' -> out.append('(')
                    ')' -> out.append(')')
                    '\\' -> out.append('\\')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }
}
