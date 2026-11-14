package com.dewangga.amplo.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the lyrics tests of the desktop app. */
class LyricsTest {
    @Test
    fun parses_the_library_format_with_headers_and_voice_prefixes() {
        val text = "[ti:Medusa]\n[ar:BMTH]\n[by:x (source: y)]\n\n[00:20.91]v1:Dead hands clutch my arm\n[00:23.44]v1:The smell\n"
        val lyrics = Lyrics.parse(text)!!
        assertTrue(lyrics.synced)
        assertEquals("Dead hands clutch my arm", lyrics.lines[0].text)
        assertEquals(20_910L, lyrics.lines[0].timeMs)
        assertEquals(2, lyrics.lines.size)
    }

    @Test
    fun handles_repeated_stamps_word_stamps_and_order() {
        val lyrics = Lyrics.parse("[00:10.00][00:02.00]Chorus\n[00:05.00]<00:05.00>One <00:05.50>two\n")!!
        assertEquals(listOf("Chorus", "One two", "Chorus"), lyrics.lines.map(LyricLine::text))
    }

    @Test
    fun word_stamps_with_padding_leave_single_spaces() {
        val text = "<00:26.55> Which <00:26.57>   <00:26.58> are <00:26.60>   <00:26.61> institutions"
        assertEquals("Which are institutions", Lyrics.cleanText(text))
    }

    @Test
    fun keeps_normal_text_with_colons_and_angle_brackets() {
        assertEquals("Note: 1 < 2 > 0", Lyrics.cleanText("Note: 1 < 2 > 0"))
        assertEquals("Hello", Lyrics.cleanText("v2:Hello"))
        assertEquals("Ooh", Lyrics.cleanText("bg:Ooh"))
    }

    @Test
    fun finds_the_active_line() {
        val lyrics = Lyrics.parse("[00:10.00]A\n[00:20.00]B\n")!!
        assertNull(lyrics.activeLine(5_000))
        assertEquals(0, lyrics.activeLine(10_000))
        assertEquals(1, lyrics.activeLine(99_000))
    }

    @Test
    fun lines_that_all_share_one_stamp_are_not_synced() {
        val lyrics = Lyrics.parse("[00:00.00]First\n[00:00.00]Second\n")!!
        assertFalse(lyrics.synced)
        assertEquals("Second", lyrics.lines[1].text)
        assertNull(lyrics.activeLine(5_000))
        assertTrue(Lyrics.parse("[00:07.00]Only line\n")!!.synced)
    }

    @Test
    fun reads_romaji_and_translations_of_lines() {
        // Base64 of "shiirudo bougu", "Shield and armor thrown away", and "Hello there".
        val text = "[ti:X]\n" +
            "[00:19.599]v1:シールド防具\n" +
            "[00:22.43]Hello there\n" +
            "[00:30.00]No extras\n" +
            "[x-romaji:19599:c2hpaXJ1ZG8gYm91Z3U=]\n" +
            "[x-translation:19599:U2hpZWxkIGFuZCBhcm1vciB0aHJvd24gYXdheQ==]\n" +
            "[x-romaji:22433:SGVsbG8gdGhlcmU=]\n" +
            "[x-romaji:99999:bm8gbGluZQ==]\n" +
            "[x-romaji:30000:!!broken!!]\n"
        val lyrics = Lyrics.parse(text)!!
        assertEquals("the tags are not lines of their own", 3, lyrics.lines.size)
        assertEquals("shiirudo bougu", lyrics.lines[0].romaji)
        assertEquals("Shield and armor thrown away", lyrics.lines[0].translation)
        assertNull("romaji that repeats the line is left out, 3 ms away too", lyrics.lines[1].romaji)
        assertNull(lyrics.lines[2].romaji)
        assertTrue(lyrics.has(Extra.Romaji) && lyrics.has(Extra.Translation))
        assertEquals(listOf(null, Extra.Romaji, Extra.Translation), lyrics.extraChoices)
        assertFalse(Lyrics.parse("[00:01.00]A\n")!!.has(Extra.Romaji))
    }

    @Test
    fun decodes_base64() {
        assertArrayEquals("Baddest".toByteArray(), Lyrics.decodeBase64("QmFkZGVzdA=="))
        assertArrayEquals("fo".toByteArray(), Lyrics.decodeBase64("Zm8"))
        assertArrayEquals("シ".toByteArray(), Lyrics.decodeBase64("44K3"))
        assertNull(Lyrics.decodeBase64("a*b"))
    }

    @Test
    fun plain_text_is_not_synced_and_empty_files_have_no_lyrics() {
        val lyrics = Lyrics.parse("[ti:X]\nFirst\nSecond\n")!!
        assertFalse(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertNull(lyrics.activeLine(5_000))
        assertNull(Lyrics.parse("[ti:X]\n\n"))
    }
}
