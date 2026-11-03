package com.music.spotui.di

import androidx.compose.runtime.snapshotFlow
import com.music.spotui.data.cache.PlaybackCacheManager
import com.music.spotui.data.cache.PlaybackCacheStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Monitors active playback queue and triggers proactive caching for the current and upcoming tracks.
 */
object QueuePreloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastTrackId: String? = null
    private var started = false

    fun init() {
        if (started) return
        started = true
        PlaybackCacheStore.init(Graph.app)
        com.music.spotui.data.NetProbe.start()
        runCatching { AudioFx.init() }
        com.music.spotui.data.NetProbe.onChange { up -> if (!up) SongPlayer.onInternetGone() }

        scope.launch {
            snapshotFlow { SongPlayer.nowPlaying.value }.collectLatest { np ->
                val uri = np.uri
                val currentTrackId = if (uri.startsWith("spotify:track:")) uri.substringAfterLast(":") else ""
                if (currentTrackId.isNotBlank()) {
                    val prev = lastTrackId
                    if (prev != null && prev != currentTrackId) {
                        PlaybackCacheManager.onTrackPlayed(prev)
                    } else if (prev == currentTrackId) {
                        PlaybackCacheManager.onTrackReplayed(currentTrackId)
                    }
                    lastTrackId = currentTrackId
                }
            }
        }

        // What comes next: Spotify's own queue first (it knows shuffle and songs queued by hand),
        // then the rest of the playlist after the song playing, in the playlist's order (Spotify's
        // queue only ever lists a few songs ahead).
        scope.launch {
            snapshotFlow { SongPlayer.engineQueue.value to SongPlayer.nowPlaying.value.contextUri }.collectLatest { (eq, ctx) ->
                val current = eq.current
                // The song playing first and at once: should the signal go, it goes on from the
                // phone instead of the music switching to a song further down.
                // (Not compared with the id the player reports: Spotify often plays another copy of
                // the same song, a relinked id, and the song playing was then never saved first.)
                if (current != null && current.spotifyTrackId.isNotBlank()) PlaybackCacheManager.preloadTracks(listOf(current))
                kotlinx.coroutines.delay(1_000) // a skip burst settles first
                val upcoming = listOfNotNull(current) + eq.queued.map { it.song } + eq.nextUp.map { it.song }
                val rest = if (ctx.startsWith("spotify:playlist:")) {
                    val list = playlistSongs(ctx.substringAfterLast(':'))
                    val at = list.indexOfFirst { it.spotifyTrackId.isNotBlank() && it.spotifyTrackId == current?.spotifyTrackId }
                    if (at >= 0) list.drop(at + 1) else emptyList()
                } else emptyList()
                val seen = HashSet<String>()
                val all = (upcoming + rest).filter { it.spotifyTrackId.isBlank() || seen.add(it.spotifyTrackId) }
                plan = all.map { it.spotifyTrackId }.filter { it.isNotBlank() }
                if (all.isNotEmpty()) PlaybackCacheManager.preloadTracks(all)
            }
        }
    }

    /** Track ids in the order they will play: the song playing first. Used when the signal goes. */
    @Volatile var plan: List<String> = emptyList()
        private set

    /** Whether [contextUri]'s fetched songs have one named [title] (another copy of a song plays under another id). */
    fun playlistHasTitle(contextUri: String, title: String): Boolean =
        contextUri.substringAfterLast(':') == listFor && listSongs.any { it.title.equals(title, ignoreCase = true) }

    /** The track ids of [contextUri] in its own order, when that playlist's songs were fetched. */
    fun playlistIds(contextUri: String): List<String>? =
        if (contextUri.startsWith("spotify:playlist:") && contextUri.substringAfterLast(':') == listFor && listSongs.isNotEmpty())
            listSongs.map { it.spotifyTrackId } else null

    // A playlist's songs, fetched once per playlist while it plays.
    private var listFor = ""
    private var listSongs: List<com.music.spotui.data.entity.SongsModel> = emptyList()

    private suspend fun playlistSongs(id: String): List<com.music.spotui.data.entity.SongsModel> {
        if (id == listFor && listSongs.isNotEmpty()) return listSongs
        var got: List<com.music.spotui.data.entity.SongsModel> = emptyList()
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                Graph.api.getPlaylistSongs(id).collect { r ->
                    if (r is com.music.spotui.data.api.Response.Success) got = r.data
                }
            }
        }
        if (got.isNotEmpty()) { listFor = id; listSongs = got }
        return got
    }
}
