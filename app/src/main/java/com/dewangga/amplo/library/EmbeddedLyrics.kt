package com.dewangga.amplo.library

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Bytes that can be read at any place, such as an open file. */
interface ByteSource {
    val size: Long

    /** Up to [length] bytes from [position]. Fewer at the end. */
    fun read(position: Long, length: Int): ByteArray
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, length: Int): ByteArray {
        if (position >= bytes.size) return ByteArray(0)
        val start = position.toInt()
        return bytes.copyOfRange(start, minOf(bytes.size, start + length))
    }
}

private class ChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long = channel.size()

    override fun read(position: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length.coerceAtMost((size - position).coerceAtLeast(0).toInt()))
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) break
        }
        return buffer.array().copyOf(buffer.position())
    }
}

/**
 * The lyrics that some songs have in their tags, read the same way as the desktop app: the
 * `LYRICS` or `UNSYNCEDLYRICS` comment of FLAC, Opus and Ogg Vorbis files, `USLT` in MP3 files
 * and `©lyr` in M4A files. Synced lyrics in them are LRC text.
 */
object EmbeddedLyrics {
    /** Tags larger than this are not read, as they hold big pictures and no lyrics. */
    private const val MAX_TAG = 64 * 1024 * 1024

    private val commentKeys = listOf("LYRICS", "UNSYNCEDLYRICS")

    /** The lyrics text in the tags of the song with this MediaStore id, or null. */
    fun read(context: Context, id: Long): String? = try {
        context.contentResolver.openFileDescriptor(songUri(id), "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { read(ChannelSource(it)) }
        }
    } catch (_: Exception) {
        null
    }

    /** The lyrics text in the tags of a song file, or null. */
    fun read(source: ByteSource): String? {
        val head = source.read(0, 12)
        val text = when {
            head.startsWith("fLaC") -> flac(source)
            head.startsWith("OggS") -> ogg(source)
            head.startsWith("ID3") -> id3(source)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4(source)
            else -> null
        }
        return text?.takeIf(String::isNotBlank)
    }

    private fun ByteArray.startsWith(magic: String) =
        size >= magic.length && magic.indices.all { this[it] == magic[it].code.toByte() }

    private fun ByteArray.int32be(at: Int) =
        (this[at].toInt() and 0xFF shl 24) or (this[at + 1].toInt() and 0xFF shl 16) or
            (this[at + 2].toInt() and 0xFF shl 8) or (this[at + 3].toInt() and 0xFF)

    private fun ByteArray.int32le(at: Int) =
        (this[at].toInt() and 0xFF) or (this[at + 1].toInt() and 0xFF shl 8) or
            (this[at + 2].toInt() and 0xFF shl 16) or (this[at + 3].toInt() and 0xFF shl 24)

    /** FLAC: metadata blocks after "fLaC". Block 4 is the Vorbis comment. */
    private fun flac(source: ByteSource): String? {
        var position = 4L
        while (position + 4 <= source.size) {
            val header = source.read(position, 4)
            if (header.size < 4) return null
            val last = header[0].toInt() and 0x80 != 0
            val kind = header[0].toInt() and 0x7F
            val length = (header[1].toInt() and 0xFF shl 16) or (header[2].toInt() and 0xFF shl 8) or (header[3].toInt() and 0xFF)
            position += 4
            if (kind == 4) return vorbisComment(source.read(position, length), 0)
            position += length
            if (last) return null
        }
        return null
    }

    /** The comments of Vorbis comment data at [start]: a vendor, then "KEY=value" entries. */
    internal fun vorbisComment(data: ByteArray, start: Int): String? {
        var at = start
        fun int(): Int? = if (at + 4 <= data.size) data.int32le(at).also { at += 4 } else null
        val vendor = int() ?: return null
        at += vendor
        val count = int() ?: return null
        val found = HashMap<String, String>()
        repeat(count) {
            val length = int() ?: return@repeat
            if (length < 0 || at + length > data.size) return@repeat
            // Most entries are short. A picture is long, and is not looked at.
            val keyEnd = (at until at + minOf(length, 64)).firstOrNull { data[it] == '='.code.toByte() }
            if (keyEnd != null) {
                val key = String(data, at, keyEnd - at, Charsets.US_ASCII).uppercase()
                if (key in commentKeys && key !in found) {
                    found[key] = String(data, keyEnd + 1, at + length - keyEnd - 1, Charsets.UTF_8)
                }
            }
            at += length
        }
        return commentKeys.firstNotNullOfOrNull { key -> found[key]?.takeIf(String::isNotBlank) }
    }

    /** Ogg: the second packet of Opus and Vorbis streams is the comment. */
    private fun ogg(source: ByteSource): String? {
        var position = 0L
        val packet = java.io.ByteArrayOutputStream()
        var packets = 0
        while (position + 27 <= source.size) {
            val header = source.read(position, 27)
            if (!header.startsWith("OggS")) return null
            val segments = header[26].toInt() and 0xFF
            val table = source.read(position + 27, segments)
            var body = position + 27 + segments
            for (lace in table) {
                val length = lace.toInt() and 0xFF
                packet.write(source.read(body, length))
                body += length
                if (length < 255) {
                    // The packet ends here.
                    packets++
                    if (packets == 2) {
                        val bytes = packet.toByteArray()
                        return when {
                            bytes.startsWith("OpusTags") -> vorbisComment(bytes, 8)
                            bytes.size > 7 && bytes[0].toInt() == 3 && String(bytes, 1, 6, Charsets.US_ASCII) == "vorbis" ->
                                vorbisComment(bytes, 7)
                            else -> null
                        }
                    }
                    packet.reset()
                }
                if (packet.size() > MAX_TAG) return null
            }
            position = body
        }
        return null
    }

    /** ID3v2 at the start of MP3 files. USLT holds the lyrics. */
    private fun id3(source: ByteSource): String? {
        val header = source.read(0, 10)
        if (header.size < 10) return null
        val version = header[3].toInt()
        if (version !in 2..4) return null
        val flags = header[5].toInt()
        val size = synchsafe(header, 6)
        if (size > MAX_TAG) return null
        var tag = source.read(10, size)
        // Unsynchronisation puts a zero after each 0xFF. The tag is read without them.
        if (flags and 0x80 != 0 && version < 4) tag = resync(tag)
        var at = 0
        if (flags and 0x40 != 0 && version >= 3) {
            // An extended header comes first.
            at += if (version == 4) synchsafe(tag, 0) else tag.int32be(0) + 4
        }
        val idLength = if (version == 2) 3 else 4
        val headerLength = if (version == 2) 6 else 10
        while (at + headerLength <= tag.size) {
            val id = String(tag, at, idLength, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') return null
            val length = when (version) {
                2 -> (tag[at + 3].toInt() and 0xFF shl 16) or (tag[at + 4].toInt() and 0xFF shl 8) or (tag[at + 5].toInt() and 0xFF)
                3 -> tag.int32be(at + 4)
                else -> synchsafe(tag, at + 4)
            }
            val frameFlags = if (version == 2) 0 else (tag[at + 9].toInt() and 0xFF)
            val start = at + headerLength
            if (length < 0 || start + length > tag.size) return null
            if (id == "USLT" || id == "ULT") {
                var frame = tag.copyOfRange(start, start + length)
                if (version == 4) {
                    if (frameFlags and 0x02 != 0) frame = resync(frame)
                    if (frameFlags and 0x01 != 0) frame = frame.copyOfRange(minOf(4, frame.size), frame.size)
                }
                val compressed = when (version) {
                    3 -> frameFlags and 0xC0 != 0
                    4 -> frameFlags and 0x0C != 0
                    else -> false
                }
                if (!compressed) uslt(frame)?.let { return it }
            }
            at = start + length
        }
        return null
    }

    private fun synchsafe(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0x7F shl 21) or (bytes[at + 1].toInt() and 0x7F shl 14) or
            (bytes[at + 2].toInt() and 0x7F shl 7) or (bytes[at + 3].toInt() and 0x7F)

    private fun resync(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var index = 0
        while (index < bytes.size) {
            out.write(bytes[index].toInt())
            if (bytes[index] == 0xFF.toByte() && index + 1 < bytes.size && bytes[index + 1].toInt() == 0) index++
            index++
        }
        return out.toByteArray()
    }

    /** USLT: the text encoding, a language, a description, then the lyrics. */
    internal fun uslt(frame: ByteArray): String? {
        if (frame.size < 5) return null
        val encoding = frame[0].toInt()
        val wide = encoding == 1 || encoding == 2
        // The description ends with a zero, two zeros for UTF-16.
        var at = 4
        if (wide) {
            while (at + 1 < frame.size && !(frame[at].toInt() == 0 && frame[at + 1].toInt() == 0)) at += 2
            at += 2
        } else {
            while (at < frame.size && frame[at].toInt() != 0) at++
            at += 1
        }
        if (at > frame.size) return null
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        // A UTF-16 text without its own byte order mark follows the one of the description.
        val text = if (encoding == 1 && !(frame.size >= at + 2 && (frame[at] == 0xFF.toByte() || frame[at] == 0xFE.toByte()))) {
            val littleEndian = frame.size > 5 && frame[4] == 0xFF.toByte() && frame[5] == 0xFE.toByte()
            String(frame, at, frame.size - at, if (littleEndian) Charsets.UTF_16LE else Charsets.UTF_16BE)
        } else {
            String(frame, at, frame.size - at, charset)
        }
        return text.trimEnd('\u0000').takeIf(String::isNotBlank)
    }

    /** MP4 and M4A: moov, udta, meta, ilst, then the "©lyr" atom with a data atom. */
    private fun mp4(source: ByteSource): String? {
        val moov = box(source, 0, source.size, "moov") ?: return null
        val udta = box(source, moov.first, moov.second, "udta") ?: return null
        val meta = box(source, udta.first, udta.second, "meta") ?: return null
        // The meta atom has four bytes of version and flags before its children.
        val ilst = box(source, meta.first + 4, meta.second, "ilst") ?: return null
        val lyrics = box(source, ilst.first, ilst.second, "©lyr") ?: return null
        val data = box(source, lyrics.first, lyrics.second, "data") ?: return null
        val length = (data.second - data.first - 8).toInt()
        if (length <= 0 || length > MAX_TAG) return null
        return String(source.read(data.first + 8, length), Charsets.UTF_8)
    }

    /** The content of the first child atom named [name] between [start] and [end], as its start and end. */
    private fun box(source: ByteSource, start: Long, end: Long, name: String): Pair<Long, Long>? {
        var position = start
        while (position + 8 <= end) {
            val header = source.read(position, 16)
            if (header.size < 8) return null
            var size = header.int32be(0).toLong() and 0xFFFFFFFFL
            var headerLength = 8
            if (size == 1L && header.size >= 16) {
                size = (header.int32be(8).toLong() shl 32) or (header.int32be(12).toLong() and 0xFFFFFFFFL)
                headerLength = 16
            } else if (size == 0L) {
                size = end - position
            }
            if (size < headerLength) return null
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (type == name) return position + headerLength to minOf(end, position + size)
            position += size
        }
        return null
    }
}
