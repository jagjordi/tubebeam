package dev.tubebeam.youtube

/** A YouTube video reference extracted from shared text. */
data class YouTubeLink(
    val videoId: String,
    val startSeconds: Int? = null,
    val playlistId: String? = null,
) {
    /** `https://www.youtube.com/watch?v=ID[&t=N][&list=L]`, tracking parameters dropped. */
    val canonicalUrl: String
        get() = buildString {
            append("https://www.youtube.com/watch?v=").append(videoId)
            startSeconds?.takeIf { it > 0 }?.let { append("&t=").append(it) }
            playlistId?.let { append("&list=").append(it) }
        }
}

/**
 * Pure-Kotlin parser for the text apps put in EXTRA_TEXT. The YouTube app shares a bare
 * `youtu.be` link; Chrome may prepend the page title. We take the first YouTube URL we find.
 */
object YouTubeLinkParser {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val playlistIdPattern = Regex("^[A-Za-z0-9_-]{2,}$")

    // Any http(s) URL, or a scheme-less YouTube host, up to the next whitespace.
    private val urlPattern = Regex(
        """(?:https?://[^\s<>"']+|(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be|youtube-nocookie\.com)/[^\s<>"']+)""",
        RegexOption.IGNORE_CASE,
    )
    private val trailingJunk = Regex("""[.,;:!?)\]'"]+$""")
    private val hms = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?$""")

    private val youtubeHosts = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtu.be", "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com",
    )

    fun parse(text: String?): YouTubeLink? {
        if (text.isNullOrBlank()) return null
        return urlPattern.findAll(text)
            .map { it.value.replace(trailingJunk, "") }
            .mapNotNull(::parseUrl)
            .firstOrNull()
    }

    private fun parseUrl(raw: String): YouTubeLink? {
        val withScheme = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
        val afterScheme = withScheme.substringAfter("://")
        val hostAndPath = afterScheme.substringBefore('#')
        val fragment = afterScheme.substringAfter('#', "")
        val host = hostAndPath.substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
        if (host !in youtubeHosts) return null

        val pathAndQuery = hostAndPath.substring(host.length.coerceAtMost(hostAndPath.length))
            .let { if (it.startsWith(":")) it.substringAfter('/', "").let { p -> "/$p" } else it }
        val path = pathAndQuery.substringBefore('?').trimEnd('/')
        val query = parseQuery(pathAndQuery.substringAfter('?', "")) + parseQuery(fragment)

        val segments = path.split('/').filter { it.isNotEmpty() }
        val videoId: String? = when {
            host.endsWith("youtu.be") -> segments.firstOrNull()
            segments.firstOrNull() == "watch" -> query["v"]
            segments.size >= 2 && segments[0] in setOf("shorts", "live", "embed", "v") -> segments[1]
            else -> null
        }
        if (videoId == null || !videoIdPattern.matches(videoId)) return null

        val start = (query["t"] ?: query["start"])?.let(::parseSeconds)
        val list = query["list"]?.takeIf { playlistIdPattern.matches(it) }
        return YouTubeLink(videoId, start, list)
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            k to v
        }

    /** Accepts `90`, `90s`, `1m30s`, `1h2m3s`. Returns null for anything else. */
    internal fun parseSeconds(value: String): Int? {
        val m = hms.matchEntire(value.trim()) ?: return null
        if (m.groupValues.drop(1).all { it.isEmpty() }) return null
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val s = m.groupValues[3].toIntOrNull() ?: 0
        return h * 3600 + min * 60 + s
    }
}
