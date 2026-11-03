package com.music.spotui.data.cache

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.offline.OfflineDownloader
import com.music.spotui.data.offline.OfflineStore
import com.music.spotui.data.preferences.CacheSettingsPref
import com.music.spotui.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles proactive preloading of the currently playing track and upcoming queue tracks into local cache,
 * and manages cache storage thresholds and post-play purging.
 */
object PlaybackCacheManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Songs being fetched now: several preload passes overlap (every queue change starts one),
    // and each song is fetched by one of them only.
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // No song count limit: only the size set in Settings (default 5 GB) limits what is kept;
    // past it, the songs played (or saved) longest ago go first.

    /**
     * Keeps [songs] (the song playing first, then the ones after it, in play order) on the phone,
     * up to [CacheSettingsPref.preloadMinutes] of listening after the current song. Fetched in
     * order, so the song playing and the next ones are ready first.
     */
    fun preloadTracks(songs: List<SongsModel>, context: Context = Graph.app) {
        if (!CacheSettingsPref.preloadingEnabled) return
        if (com.music.spotui.data.DataSaver.active || !com.music.spotui.data.NetProbe.online(context)) return

        val capMb = CacheSettingsPref.cacheCapMb
        val maxCapBytes = if (capMb > 0) capMb * 1024L * 1024L else 0L

        val aheadMs = CacheSettingsPref.preloadMinutes * 60_000L
        val wanted = ArrayList<SongsModel>()
        var total = 0L
        for ((i, song) in songs.withIndex()) {
            if (i > 0 && total >= aheadMs) break
            if (song.spotifyTrackId.isBlank()) continue
            wanted += song
            // The song playing does not count towards the time ahead.
            if (i > 0) total += song.durationMs.toLong().takeIf { it > 0 } ?: 210_000L
        }
        // The ones wanted are never the ones the cap throws out to make room.
        val keep = wanted.map { it.spotifyTrackId }.toSet()
        if (maxCapBytes > 0) PlaybackCacheStore.enforceCap(maxCapBytes, keep)

        val toPreload = wanted.filter { song ->
            val id = song.spotifyTrackId
            !OfflineStore.isDownloaded(id) && !PlaybackCacheStore.isCached(id) && id !in inFlight
        }
        if (toPreload.isEmpty()) return

        scope.launch {
            for (song in toPreload) {
                val id = song.spotifyTrackId
                if (OfflineStore.isDownloaded(id) || PlaybackCacheStore.isCached(id)) continue
                if (!com.music.spotui.data.NetProbe.online(context)) break
                if (!inFlight.add(id)) continue
                try {
                    runCatching {
                        val audioFile = PlaybackCacheStore.createCacheFile(id)
                        val hit = com.music.spotui.video.YouTubeSource.findSong(song.title, song.singer, song.durationMs.toLong())
                            ?: com.music.spotui.video.YouTubeSource.findAudio(song.title, song.singer, song.durationMs.toLong())
                        if (hit != null) {
                            com.music.spotui.video.MusicVideoController.downloadAudioFile(hit.url, audioFile, best = true)
                        } else {
                            val fallback = OfflineDownloader.fetchFallbackAudioFile(id, song.title, song.singer, song.durationMs.toLong())
                            if (fallback != null && fallback.isFile && fallback.length() > 10_000L) {
                                fallback.copyTo(audioFile, overwrite = true)
                            }
                        }
                        if (audioFile.isFile && audioFile.length() > 10_000L) {
                            PlaybackCacheStore.addTrack(
                                CachedTrack(
                                    trackId = id,
                                    title = song.title,
                                    artist = song.singer,
                                    album = song.album,
                                    coverUrl = song.coverUri,
                                    durationMs = song.durationMs.toLong(),
                                    fileName = audioFile.name,
                                    bytes = audioFile.length(),
                                    cachedAt = System.currentTimeMillis(),
                                )
                            )
                            if (maxCapBytes > 0) PlaybackCacheStore.enforceCap(maxCapBytes, keep)
                            app.myspoti.host.android.Logbook.log("saved for offline: ${song.title} (${audioFile.length() / 1024} KB, ${PlaybackCacheStore.cachedTracks.size} songs kept)")
                        } else {
                            audioFile.delete()
                        }
                    }.onFailure {
                        app.myspoti.host.android.Logbook.log("not saved for offline: ${song.title}: ${it.message?.take(80)}")
                        android.util.Log.w("PlaybackCache", "Failed to preload $id (${song.title}): ${it.message}")
                    }
                } finally {
                    inFlight.remove(id)
                }
            }
        }
    }

    /**
     * A song played to its end or skipped: kept (it used to be deleted 15 s later), only marked as
     * played now, so the songs played least recently are the ones the size limit drops.
     */
    fun onTrackPlayed(trackId: String) {
        if (trackId.isBlank()) return
        PlaybackCacheStore.touch(trackId)
    }

    /** The first songs of a playlist or album the user opens: saved too, so a tap there works with no signal. */
    fun preloadOpened(songs: List<SongsModel>, context: Context = Graph.app) {
        val first = songs.filter { it.spotifyTrackId.isNotBlank() }.take(OPENED_SONGS)
        if (first.isEmpty()) return
        // One by one, as the playlist ahead is: the first song counts as "playing", the rest as ahead.
        preloadTracks(first, context)
    }
    private const val OPENED_SONGS = 5

    fun onTrackReplayed(trackId: String) {
        if (trackId.isBlank()) return
        PlaybackCacheStore.cancelPendingPurge(trackId)
    }
}
