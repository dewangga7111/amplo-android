package com.dewangga.amplo.library

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.roundToLong

/** What some lyrics downloaders add under a line: the line in Latin letters, or in another language. */
enum class Extra(val label: String) {
    Romaji("Romaji"),
    Translation("Translation"),
}

/** One line of lyrics. [timeMs] is 0 for lyrics without timestamps. */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    /** The line in Latin letters, for lyrics in Japanese and other scripts. */
    val romaji: String? = null,
    val translation: String? = null,
) {
    fun extra(extra: Extra): String? = when (extra) {
        Extra.Romaji -> romaji
        Extra.Translation -> translation
    }
}

/** Lyrics from an `.lrc` or `.txt` file, read the same way as the desktop app. */
data class Lyrics(
    /** False when the file has no timestamps. All line times are then zero. */
    val synced: Boolean,
    val lines: List<LyricLine>,
) {
    /** True when some line has this extra. */
    fun has(extra: Extra): Boolean = lines.any { it.extra(extra) != null }

    /** What can show under the lines, starting with nothing. */
    val extraChoices: List<Extra?> get() = listOf<Extra?>(null) + Extra.entries.filter(::has)

    /** The index of the line sung at [positionMs]. Before the first line, and in lyrics without times, there is none. */
    fun activeLine(positionMs: Long): Int? {
        if (!synced) return null
        // The first line that starts after the position, less one.
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = (low + high) / 2
            if (lines[middle].timeMs <= positionMs) low = middle + 1 else high = middle
        }
        return (low - 1).takeIf { it >= 0 }
    }

    companion object {
        /** A tag's time may be rounded differently from the line's own stamp. */
        private const val EXTRA_TOLERANCE_MS = 10L

        /** Null when the text has no lines. */
        fun parse(text: String): Lyrics? {
            val timed = ArrayList<LyricLine>()
            val plain = ArrayList<String>()
            val romaji = TreeMap<Long, String>()
            val translations = TreeMap<Long, String>()
            for (raw in text.removePrefix("﻿").lines()) {
                var rest = raw.trim()
                val times = ArrayList<Long>()
                while (true) {
                    val (time, after) = leadingTimestamp(rest) ?: break
                    times += time
                    rest = after.trimStart()
                }
                if (times.isEmpty() && rest.startsWith('[')) {
                    // A header such as [ti:Title], or the romaji or translation of a line.
                    extraTag(rest)?.let { (kind, millis, extra) ->
                        when (kind) {
                            Extra.Romaji -> romaji[millis] = extra
                            Extra.Translation -> translations[millis] = extra
                        }
                    }
                    continue
                }
                val line = cleanText(rest)
                if (line.isEmpty()) continue
                if (times.isEmpty()) plain += line else times.mapTo(timed) { LyricLine(it, line) }
            }

            if (timed.isEmpty()) {
                return if (plain.isEmpty()) null else Lyrics(false, plain.map { LyricLine(0, it) })
            }
            fun near(extras: TreeMap<Long, String>, time: Long): String? =
                extras.subMap(time - EXTRA_TOLERANCE_MS, true, time + EXTRA_TOLERANCE_MS, true)
                    .minByOrNull { abs(it.key - time) }?.value
            val withExtras = timed.map { line ->
                // A line in English has itself as its romaji, which would only repeat it.
                line.copy(
                    romaji = near(romaji, line.timeMs)?.takeUnless { sameWords(it, line.text) },
                    translation = near(translations, line.timeMs)?.takeUnless { sameWords(it, line.text) },
                )
            }
            // Some downloads stamp every line [00:00.00]. That file order is all they really know.
            val first = withExtras[0].timeMs
            if (withExtras.size > 1 && withExtras.all { it.timeMs == first }) {
                return Lyrics(false, withExtras.map { it.copy(timeMs = 0) })
            }
            // `sortedBy` is stable, so lines with the same time keep the order of the file.
            return Lyrics(true, withExtras.sortedBy { it.timeMs })
        }

        /** Parses `[mm:ss.xx]` at the start of the text. */
        private fun leadingTimestamp(text: String): Pair<Long, String>? {
            if (!text.startsWith('[')) return null
            val end = text.indexOf(']')
            if (end < 0) return null
            val time = parseTime(text.substring(1, end)) ?: return null
            return time to text.substring(end + 1)
        }

        /** "mm:ss.xx", or "mm:ss:xx" as some files write it, in milliseconds. */
        private fun parseTime(stamp: String): Long? {
            val colon = stamp.indexOf(':')
            if (colon < 0) return null
            val minutesText = stamp.substring(0, colon)
            if (minutesText.isEmpty() || !minutesText.all(Char::isDigit)) return null
            val minutes = minutesText.toLongOrNull() ?: return null
            val secondsText = stamp.substring(colon + 1).replace(':', '.')
            if (secondsText.isEmpty() || !secondsText.all { it.isDigit() || it == '.' }) return null
            val seconds = secondsText.toDoubleOrNull() ?: return null
            if (!seconds.isFinite() || seconds < 0) return null
            return minutes * 60_000 + (seconds * 1000).roundToLong()
        }

        /** Reads `[x-romaji:<ms>:<base64>]` or `[x-translation:<ms>:<base64>]`. */
        private fun extraTag(text: String): Triple<Extra, Long, String>? {
            if (!text.startsWith('[') || !text.endsWith(']')) return null
            val inner = text.substring(1, text.length - 1)
            val name = inner.substringBefore(':', "")
            val kind = when (name.trim()) {
                "x-romaji" -> Extra.Romaji
                "x-translation" -> Extra.Translation
                else -> return null
            }
            val rest = inner.substringAfter(':')
            if (!rest.contains(':')) return null
            val millis = rest.substringBefore(':').trim().toLongOrNull() ?: return null
            val bytes = decodeBase64(rest.substringAfter(':').trim()) ?: return null
            val decoder = Charsets.UTF_8.newDecoder()
            val decoded = try {
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                return null
            }
            val extra = cleanText(decoded)
            return if (extra.isEmpty()) null else Triple(kind, millis, extra)
        }

        /** Both base64 alphabets, with or without padding. */
        internal fun decodeBase64(text: String): ByteArray? {
            val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
            var bits = 0
            var count = 0
            for (char in text) {
                val value = when (char) {
                    in 'A'..'Z' -> char - 'A'
                    in 'a'..'z' -> char - 'a' + 26
                    in '0'..'9' -> char - '0' + 52
                    '+', '-' -> 62
                    '/', '_' -> 63
                    '=', ' ', '\t' -> continue
                    else -> return null
                }
                bits = (bits shl 6) or value
                count += 6
                if (count >= 8) {
                    count -= 8
                    out.write((bits shr count) and 0xFF)
                }
            }
            return out.toByteArray()
        }

        /** True when two lines differ only in case and spacing, as an English line and its "romaji" do. */
        private fun sameWords(first: String, second: String): Boolean {
            fun words(text: String) = text.split(Regex("\\s+")).filter(String::isNotEmpty).map(String::lowercase)
            return words(first) == words(second)
        }

        /** Removes the per-word `<mm:ss.xx>` stamps and the `v1:` or `bg:` voice prefixes. */
        internal fun cleanText(text: String): String {
            val out = StringBuilder(text.length)
            var rest = text
            while (true) {
                val start = rest.indexOf('<')
                if (start < 0) break
                out.append(rest, 0, start)
                val after = rest.substring(start + 1)
                val end = after.indexOf('>')
                if (end >= 0 && parseTime(after.substring(0, end)) != null) {
                    rest = after.substring(end + 1)
                } else {
                    out.append('<')
                    rest = after
                }
            }
            out.append(rest)
            val trimmed = out.toString().trim()
            val prefix = trimmed.substringBefore(':', "")
            val isVoice = trimmed.contains(':') &&
                (prefix == "bg" || (prefix.startsWith('v') && prefix.length > 1 && prefix.drop(1).all { it in '0'..'9' }))
            val withoutVoice = if (isVoice) trimmed.substringAfter(':') else trimmed
            // Word-stamped files pad every stamp with spaces, which would leave wide gaps.
            return withoutVoice.split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
        }
    }
}
