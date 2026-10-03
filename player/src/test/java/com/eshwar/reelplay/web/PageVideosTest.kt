package com.eshwar.reelplay.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageVideosTest {

    private val page = "https://example.com/films/index.html"

    @Test
    fun findsLinkedEmbeddedAndTorrentVideos_resolvedAndDeduplicated() {
        val html = """
            <a href="clips/My%20Trip.mp4">Trip</a>
            <a href='/media/movie.mkv?token=1&amp;x=2'>Movie</a>
            <video src="https://cdn.example.com/v/intro.webm"></video>
            <source data-src="//cdn.example.com/live/master.m3u8">
            <a href="clips/My%20Trip.mp4">again</a>
            <a href="files/show.torrent">torrent</a>
            <a href="magnet:?xt=urn:btih:abc&amp;dn=Big%20Film">magnet</a>
            <a href="about.html">About</a> <img src="poster.jpg">
            <script>var u = "https:\/\/cdn.example.com\/v\/extra.mp4";</script>
        """.trimIndent()

        val found = PageVideos.extract(html, page)

        assertEquals(
            listOf(
                "https://example.com/films/clips/My%20Trip.mp4" to FoundVideo.Kind.FILE,
                "https://example.com/media/movie.mkv?token=1&x=2" to FoundVideo.Kind.FILE,
                "https://cdn.example.com/v/intro.webm" to FoundVideo.Kind.FILE,
                "https://cdn.example.com/live/master.m3u8" to FoundVideo.Kind.STREAM,
                "https://example.com/films/files/show.torrent" to FoundVideo.Kind.TORRENT,
                "magnet:?xt=urn:btih:abc&dn=Big%20Film" to FoundVideo.Kind.MAGNET,
                "https://cdn.example.com/v/extra.mp4" to FoundVideo.Kind.FILE,
            ),
            found.map { it.url to it.kind },
        )
        assertEquals("My Trip.mp4", found[0].name)
        assertEquals("Big Film", found.single { it.kind == FoundVideo.Kind.MAGNET }.name)
    }

    @Test
    fun ignoresNonVideoLinks() {
        assertNull(PageVideos.classify("javascript:void(0)", page))
        assertNull(PageVideos.classify("#top", page))
        assertNull(PageVideos.classify("style.css", page))
        assertNull(PageVideos.classify("ftp://x/y.mp4", page))
    }

    @Test
    fun normalizesTypedLinks_andPullsLinksOutOfSharedText() {
        assertEquals("https://example.com/a", PageVideos.normalize(" example.com/a "))
        assertEquals("http://x.org", PageVideos.normalize("http://x.org"))
        assertEquals(
            "https://example.com/watch?id=3",
            FindVideosActivity.firstLink("Look at this https://example.com/watch?id=3"),
        )
    }
}
