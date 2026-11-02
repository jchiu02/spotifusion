package app.myspoti.host.android

/**
 * What the hidden Web Player page does not need to load: the app shows its own screens, so the
 * page's pictures, looping Canvas videos, web fonts and tracking only cost memory, CPU and data.
 * Scripts, the page itself, Spotify's APIs, DRM licences and the audio are never touched.
 */
internal object LitePage {
    /** Turn the whole thing off here if a Spotify change ever needs one of these back. */
    const val ENABLED = true

    private val imageExt = Regex("""\.(jpe?g|png|webp|gif|avif|ico)$""", RegexOption.IGNORE_CASE)
    private val fontExt = Regex("""\.(woff2?|ttf|otf)$""", RegexOption.IGNORE_CASE)
    private val imageHosts = listOf("i.scdn.co", "mosaic.scdn.co", "seeded-session-images.scdn.co", "image-cdn-ak.spotifycdn.com", "image-cdn-fa.spotifycdn.com", "lineup-images.scdn.co", "thisis-images.spotifycdn.com", "pickasso.spotifycdn.com", "blend-playlist-covers.spotifycdn.com", "daily-mix.scdn.co", "newjams-images.scdn.co", "charts-images.scdn.co", "t.scdn.co")
    private val videoHosts = listOf("canvaz.scdn.co", "canvaz.spotifycdn.com")
    private val trackingHosts = listOf("sentry.io", "google-analytics.com", "googletagmanager.com", "hotjar.com", "fastly-insights.com", "branch.io")
    private val trackingPaths = listOf("/gabo-receiver-service/", "/pixel/", "/track/v1/")

    /** The kind of thing [url] is, when it is one the page goes without; null to load it. */
    fun blocked(url: String, accept: String?): String? {
        if (!ENABLED) return null
        val u = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        val path = u.path.orEmpty()
        return when {
            videoHosts.any { host == it || host.endsWith(".$it") } -> "Canvas videos"
            imageHosts.any { host == it } || imageExt.containsMatchIn(path) ||
                accept?.startsWith("image/") == true -> "pictures"
            fontExt.containsMatchIn(path) -> "web fonts"
            trackingHosts.any { host == it || host.endsWith(".$it") } || trackingPaths.any { path.contains(it) } -> "tracking"
            else -> null
        }
    }
}
