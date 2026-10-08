package dev.tubebeam.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinkParserTest {
    private val id = "dQw4w9WgXcQ"
    private fun idOf(text: String) = YouTubeLinkParser.parse(text)?.videoId
    private fun canonical(text: String) = YouTubeLinkParser.parse(text)?.canonicalUrl

    @Test fun youtuBe() = assertEquals(id, idOf("https://youtu.be/$id"))
    @Test fun youtuBeWithTracking() = assertEquals("https://www.youtube.com/watch?v=$id", canonical("https://youtu.be/$id?si=abc123"))
    @Test fun watch() = assertEquals(id, idOf("https://www.youtube.com/watch?v=$id"))
    @Test fun watchNoWww() = assertEquals(id, idOf("https://youtube.com/watch?v=$id"))
    @Test fun mobile() = assertEquals(id, idOf("https://m.youtube.com/watch?v=$id&feature=share"))
    @Test fun music() = assertEquals(id, idOf("https://music.youtube.com/watch?v=$id&list=RDAMVM$id"))
    @Test fun shorts() = assertEquals(id, idOf("https://www.youtube.com/shorts/$id"))
    @Test fun live() = assertEquals(id, idOf("https://www.youtube.com/live/$id?feature=share"))
    @Test fun embed() = assertEquals(id, idOf("https://www.youtube.com/embed/$id"))
    @Test fun noCookieEmbed() = assertEquals(id, idOf("https://www.youtube-nocookie.com/embed/$id"))
    @Test fun http() = assertEquals(id, idOf("http://youtu.be/$id"))
    @Test fun schemeless() = assertEquals(id, idOf("youtu.be/$id"))

    @Test fun chromeShareWithTitle() =
        assertEquals(id, idOf("Rick Astley - Never Gonna Give You Up https://www.youtube.com/watch?v=$id"))

    @Test fun urlInsideSentenceWithTrailingPunctuation() =
        assertEquals(id, idOf("look at this (https://youtu.be/$id)."))

    @Test fun firstYouTubeUrlWins() =
        assertEquals(id, idOf("https://example.com/x https://youtu.be/$id https://youtu.be/AAAAAAAAAAA"))

    @Test fun timeSeconds() = assertEquals(90, YouTubeLinkParser.parse("https://youtu.be/$id?t=90")?.startSeconds)
    @Test fun timeSecondsSuffix() = assertEquals(90, YouTubeLinkParser.parse("https://youtu.be/$id?t=90s")?.startSeconds)
    @Test fun timeMinutesSeconds() = assertEquals(90, YouTubeLinkParser.parse("https://youtu.be/$id?t=1m30s")?.startSeconds)
    @Test fun timeHms() = assertEquals(3723, YouTubeLinkParser.parse("https://www.youtube.com/watch?v=$id&t=1h2m3s")?.startSeconds)
    @Test fun startParam() = assertEquals(12, YouTubeLinkParser.parse("https://www.youtube.com/embed/$id?start=12")?.startSeconds)
    @Test fun fragmentTime() = assertEquals(30, YouTubeLinkParser.parse("https://youtu.be/$id#t=30")?.startSeconds)
    @Test fun garbageTimeIgnored() = assertNull(YouTubeLinkParser.parse("https://youtu.be/$id?t=abc")?.startSeconds)
    @Test fun zeroTimeOmitted() = assertEquals("https://www.youtube.com/watch?v=$id", canonical("https://youtu.be/$id?t=0"))

    @Test fun canonicalWithTimeAndList() =
        assertEquals(
            "https://www.youtube.com/watch?v=$id&t=42&list=PLabc_123",
            canonical("https://www.youtube.com/watch?v=$id&list=PLabc_123&t=42&pp=xyz&si=1"),
        )

    @Test fun playlistKept() = assertEquals("PLabc_123", YouTubeLinkParser.parse("https://youtu.be/$id?list=PLabc_123")?.playlistId)

    @Test fun invalidIdLength() = assertNull(idOf("https://youtu.be/short"))
    @Test fun invalidIdChars() = assertNull(idOf("https://www.youtube.com/watch?v=dQw4w9WgXc!"))
    @Test fun channelUrl() = assertNull(idOf("https://www.youtube.com/@SomeChannel"))
    @Test fun playlistOnly() = assertNull(idOf("https://www.youtube.com/playlist?list=PLabc"))
    @Test fun notYouTube() = assertNull(idOf("https://vimeo.com/12345678"))
    @Test fun lookalikeHost() = assertNull(idOf("https://notyoutube.com/watch?v=$id"))
    @Test fun plainText() = assertNull(idOf("hello world"))
    @Test fun empty() = assertNull(idOf(""))
    @Test fun nullText() = assertNull(YouTubeLinkParser.parse(null))
}
