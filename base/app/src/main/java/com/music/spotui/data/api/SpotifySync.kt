package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import app.myspoti.host.android.MyspotiRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Library and playlist edits on the user's Spotify account. Failures are logged and
 * reported through the callbacks; the UI has already shown the change.
 */
object SpotifySync {
    private const val TAG = "SpotifySync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val api get() = MyspotiRuntime.api

    /** The app changed the user's library (a playlist created): Library screens reload. */
    val libraryChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Ids of playlists whose songs the app changed: an open playlist page reloads. */
    val playlistChanges = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)

    // Playlists created a moment ago, kept in the Library until Spotify's own list has them
    // (the library listing lags behind a create by a few seconds).
    private const val CREATED_GRACE_MS = 60_000L
    private val recentlyCreated = ConcurrentHashMap<String, Pair<com.music.spotui.data.entity.LibraryEntry, Long>>()

    /** [list] (the Library's root level) with any just-created playlist it is missing, after Liked Songs. */
    fun withRecentlyCreated(list: List<com.music.spotui.data.entity.LibraryEntry>): List<com.music.spotui.data.entity.LibraryEntry> {
        val now = System.currentTimeMillis()
        recentlyCreated.entries.removeIf { now - it.value.second > CREATED_GRACE_MS }
        val have = list.map { it.spotifyId }.toSet()
        val missing = recentlyCreated.values.sortedByDescending { it.second }.map { it.first }.filter { it.spotifyId !in have }
        if (missing.isEmpty()) return list
        val at = if (list.firstOrNull()?.spotifyId == Api.LIKED_SONGS_ID) 1 else 0
        return list.take(at) + missing + list.drop(at)
    }

    fun setTrackSaved(context: Context, trackId: String, saved: Boolean) {
        if (trackId.isNotBlank()) com.music.spotui.di.SongPlayer.setTrackSaved(trackId, saved)
    }

    fun setAlbumSaved(context: Context, albumId: String, saved: Boolean) {
        if (albumId.isNotBlank()) com.music.spotui.di.SongPlayer.setSaved("spotify:album:$albumId", saved)
    }

    fun setArtistFollowed(context: Context, artistId: String, followed: Boolean) {
        if (artistId.isNotBlank()) com.music.spotui.di.SongPlayer.setSaved("spotify:artist:$artistId", followed)
    }

    /** Adds a track to one of the user's playlists. */
    fun addTrackToPlaylist(context: Context, playlistId: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank() || trackId.isBlank()) { onDone(false); return }
        scope.launch {
            val ok = runCatching { api.playlists.addTracks("spotify:playlist:$playlistId", listOf("spotify:track:$trackId")) }
                .onFailure { Log.w(TAG, "add $trackId to $playlistId failed: ${it.message}") }.isSuccess
            if (ok) { membershipCache[playlistId]?.add(trackId); playlistChanges.tryEmit(playlistId) }
            onDone(ok)
        }
    }

    /** Removes every occurrence of a track from one of the user's playlists. */
    fun removeTrackFromPlaylist(context: Context, playlistId: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank() || trackId.isBlank()) { onDone(false); return }
        scope.launch {
            val ok = runCatching {
                val uri = "spotify:playlist:$playlistId"
                val uids = allTracks(uri).filter { it.uri == "spotify:track:$trackId" }.mapNotNull { it.uid }
                check(uids.isNotEmpty()) { "track not in playlist" }
                api.playlists.removeTracks(uri, uids)
            }.onFailure { Log.w(TAG, "remove $trackId from $playlistId failed: ${it.message}") }.isSuccess
            if (ok) { membershipCache[playlistId]?.remove(trackId); playlistChanges.tryEmit(playlistId) }
            onDone(ok)
        }
    }

    /** Creates a playlist (and adds the track when given). */
    fun createPlaylistWithTrack(context: Context, name: String, trackId: String, folderUri: String? = null, onDone: (Boolean) -> Unit = {}) {
        if (name.isBlank()) { onDone(false); return }
        scope.launch {
            val ok = runCatching {
                val uri = api.playlists.create(name.trim(), folderUri)
                val id = uri.substringAfterLast(':')
                if (folderUri == null) {
                    // On screen at once, before Spotify's library listing has caught up.
                    val entry = com.music.spotui.data.entity.LibraryEntry(
                        spotifyId = id,
                        name = name.trim(),
                        subtitle = context.getString(app.myspoti.engine.R.string.assisted_curation_search_playlist),
                        coverUri = "",
                        isPlaylist = true,
                        uri = uri,
                    )
                    recentlyCreated[id] = entry to System.currentTimeMillis()
                    Api.HomeCache.libraryRoot = Api.HomeCache.libraryRoot?.let(::withRecentlyCreated)
                }
                if (trackId.isNotBlank()) {
                    api.playlists.addTracks(uri, listOf("spotify:track:$trackId"))
                    membershipCache[id] = ConcurrentHashMap.newKeySet<String>().apply { add(trackId) }
                }
            }.onFailure { Log.w(TAG, "create playlist '$name' failed: ${it.message}") }.isSuccess
            folderUri?.let { Api.HomeCache.folders.remove(it) }
            if (ok) libraryChanges.tryEmit(Unit)
            onDone(ok)
        }
    }

    private suspend fun allTracks(uri: String): List<app.myspoti.api.Track> {
        val out = ArrayList<app.myspoti.api.Track>()
        var offset = 0
        while (true) {
            val page = api.playlists.tracks(uri, offset, 100)
            out += page.items
            offset = page.nextOffset ?: break
        }
        return out
    }

    // Which playlists contain a track, cached per playlist for the session.
    private val membershipCache = ConcurrentHashMap<String, MutableSet<String>>()

    /** Track ids contained in [playlistId] (first call fetches, then cached). */
    suspend fun playlistTrackIds(context: Context, playlistId: String): Set<String> {
        membershipCache[playlistId]?.let { return it }
        val ids = runCatching { allTracks("spotify:playlist:$playlistId").map { it.uri.substringAfterLast(':') } }.getOrNull() ?: return emptySet()
        val set = ConcurrentHashMap.newKeySet<String>().apply { addAll(ids) }
        membershipCache[playlistId] = set
        return set
    }
}
