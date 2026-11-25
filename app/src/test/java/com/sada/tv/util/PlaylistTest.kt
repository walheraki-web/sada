package com.sada.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTest {

    @Test fun parsesExtendedM3u() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="FOX",FoX FM Live 1
            http://example.com/fox.m3u8
            #EXTINF:-1,إذاعة
            http://example.com/radio.mp3
        """.trimIndent()
        val s = Playlist.parse(m3u)
        assertEquals(2, s.size)
        assertEquals("FoX FM Live 1", s[0].name)
        assertEquals("http://example.com/fox.m3u8", s[0].url)
        assertEquals("إذاعة", s[1].name)
    }

    @Test fun parsesCfgNameCommaUrl() {
        val cfg = """
            FoX FM,http://a/f.m3u8
            BBC|https://b/bbc.mp3
            Radio=http://c/r
        """.trimIndent()
        val s = Playlist.parse(cfg)
        assertEquals(3, s.size)
        assertEquals("FoX FM", s[0].name)
        assertEquals("https://b/bbc.mp3", s[1].url)
        assertEquals("http://c/r", s[2].url)
    }

    @Test fun parsesBareUrlsAndNameLines() {
        val txt = """
            http://x/stream1
            My Station
            http://y/stream2
        """.trimIndent()
        val s = Playlist.parse(txt)
        assertEquals(2, s.size)
        assertEquals("http://x/stream1", s[0].url)
        assertEquals("My Station", s[1].name)
        assertEquals("http://y/stream2", s[1].url)
    }

    @Test fun dedupesByUrl() {
        val s = Playlist.parse("A,http://same\nB,http://same")
        assertEquals(1, s.size)
    }

    @Test fun parsesIpaudioCfg() {
        val cfg = """
            # ipaudio: 0|k|
            0|FoX FM Live 1|http://foxfm.xyz:8080/live1#
            0|FoX FM 2|http://foxfm.xyz/2#
            0|FoX FM EN 1|http://foxfm.xyz:8080/en1#
        """.trimIndent()
        val s = Playlist.parse(cfg)
        assertEquals(3, s.size)
        assertEquals("FoX FM Live 1", s[0].name)
        assertEquals("http://foxfm.xyz:8080/live1", s[0].url) // trailing # stripped
        assertEquals("FoX FM EN 1", s[2].name)
    }
}
