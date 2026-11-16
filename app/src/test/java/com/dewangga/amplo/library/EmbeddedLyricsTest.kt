package com.dewangga.amplo.library

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedLyricsTest {
    private val lrc = "[00:01.00]First line\n[00:02.50]Second line\n"

    private fun bytes(build: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(build).toByteArray()
    private fun ByteArrayOutputStream.text(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))
    private fun ByteArrayOutputStream.le32(value: Int) = repeat(4) { write(value shr (8 * it) and 0xFF) }
    private fun ByteArrayOutputStream.be32(value: Int) = repeat(4) { write(value shr (8 * (3 - it)) and 0xFF) }
    private fun ByteArrayOutputStream.synchsafe(value: Int) = repeat(4) { write(value shr (7 * (3 - it)) and 0x7F) }

    private fun comment(vararg entries: String) = bytes {
        le32(6)
        text("vendor")
        le32(entries.size)
        for (entry in entries) {
            val utf8 = entry.toByteArray()
            le32(utf8.size)
            write(utf8)
        }
    }

    private fun read(file: ByteArray) = EmbeddedLyrics.read(ByteArraySource(file))

    @Test
    fun readsTheLyricsCommentOfFlacFiles() {
        val streamInfo = ByteArray(34)
        val tags = comment("TITLE=Song", "lyrics=$lrc", "METADATA_BLOCK_PICTURE=" + "A".repeat(500))
        val flac = bytes {
            text("fLaC")
            write(0x00) // STREAMINFO, not the last block
            write(0); write(0); write(34)
            write(streamInfo)
            write(0x84) // Vorbis comment, the last block
            write(tags.size shr 16); write(tags.size shr 8 and 0xFF); write(tags.size and 0xFF)
            write(tags)
        }
        assertEquals(lrc, read(flac))
        val lyrics = Lyrics.parse(read(flac)!!)!!
        assertEquals(true, lyrics.synced)
        assertEquals("First line", lyrics.lines[0].text)
    }

    @Test
    fun prefersLyricsToUnsyncedLyricsAndSkipsEmptyOnes() {
        assertEquals(lrc, EmbeddedLyrics.vorbisComment(comment("UNSYNCEDLYRICS=Plain", "LYRICS=$lrc"), 0))
        assertEquals("Plain", EmbeddedLyrics.vorbisComment(comment("LYRICS=  ", "UNSYNCEDLYRICS=Plain"), 0))
        assertNull(EmbeddedLyrics.vorbisComment(comment("TITLE=Song"), 0))
    }

    /** One Ogg page with the given packets, each laced in segments of 255. */
    private fun oggPage(vararg packets: ByteArray) = bytes {
        val lacing = ArrayList<Int>()
        for (packet in packets) {
            var left = packet.size
            while (left >= 255) {
                lacing += 255
                left -= 255
            }
            lacing += left
        }
        text("OggS")
        write(ByteArray(22))
        write(lacing.size)
        lacing.forEach(::write)
        packets.forEach(::write)
    }

    @Test
    fun readsTheOpusTagsPacketAcrossPages() {
        val head = "OpusHead".toByteArray() + ByteArray(11)
        val tags = "OpusTags".toByteArray() + comment("ARTIST=Band", "LYRICS=$lrc", "PAD=" + "x".repeat(600))
        // The tags packet starts on the second page and is long enough for several segments.
        assertEquals(lrc, read(oggPage(head) + oggPage(tags)))
    }

    private fun uslt(encoding: Int, text: ByteArray, description: ByteArray) = bytes {
        write(encoding)
        text("eng")
        write(description)
        write(text)
    }

    private fun id3(version: Int, frame: ByteArray) = bytes {
        val frameBytes = bytes {
            text("TIT2")
            if (version == 4) synchsafe(5) else be32(5)
            write(0); write(0)
            write(3); text("Song")
            text("USLT")
            if (version == 4) synchsafe(frame.size) else be32(frame.size)
            write(0); write(0)
            write(frame)
        }
        text("ID3")
        write(version); write(0); write(0)
        synchsafe(frameBytes.size + 20)
        write(frameBytes)
        write(ByteArray(20)) // padding
        write(byteArrayOf(0xFF.toByte(), 0xFB.toByte())) // audio
    }

    @Test
    fun readsUsltFramesOfMp3Files() {
        val utf8 = uslt(3, lrc.toByteArray(), byteArrayOf(0))
        assertEquals(lrc, read(id3(4, utf8)))
        val utf16 = uslt(1, lrc.toByteArray(Charsets.UTF_16), "d".toByteArray(Charsets.UTF_16) + byteArrayOf(0, 0))
        assertEquals(lrc, read(id3(3, utf16)))
        val latin = uslt(0, "Plain words".toByteArray(Charsets.ISO_8859_1), "desc".toByteArray() + byteArrayOf(0))
        assertEquals("Plain words", read(id3(3, latin)))
    }

    private fun atom(type: String, vararg children: ByteArray) = bytes {
        val content = children.fold(ByteArray(0)) { all, child -> all + child }
        be32(content.size + 8)
        write(type.toByteArray(Charsets.ISO_8859_1))
        write(content)
    }

    @Test
    fun readsTheLyricsAtomOfM4aFiles() {
        val data = atom("data", bytes { be32(1); be32(0) }, lrc.toByteArray())
        val meta = atom("meta", ByteArray(4), atom("hdlr", ByteArray(25)), atom("ilst", atom("©nam", atom("data", ByteArray(12))), atom("©lyr", data)))
        val file = atom("ftyp", "M4A ".toByteArray(), ByteArray(4)) + atom("mdat", ByteArray(40)) + atom("moov", atom("mvhd", ByteArray(20)), atom("udta", meta))
        assertEquals(lrc, read(file))
    }

    @Test
    fun filesWithoutLyricsHaveNone() {
        assertNull(read(ByteArray(0)))
        assertNull(read("RIFF....WAVE".toByteArray()))
        assertNull(read(id3(3, uslt(3, "  ".toByteArray(), byteArrayOf(0)))))
    }
}
