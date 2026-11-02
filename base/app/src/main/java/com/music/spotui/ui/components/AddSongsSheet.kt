package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.ui.theme.AppAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One song the sheet can add. */
private data class Candidate(val id: String, val title: String, val artists: String, val cover: String)

/**
 * "Add to this playlist" on a playlist the user can change (theirs or collaborative): search
 * Spotify, or pick from songs like the ones already in it, and add with one tap, without
 * leaving the playlist.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun AddSongsSheet(
    playlistUri: String,
    playlistName: String,
    inPlaylist: Set<String>,
    seedIds: List<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playlistId = playlistUri.substringAfterLast(':')
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Candidate>?>(null) }
    var suggested by remember { mutableStateOf<List<Candidate>?>(null) }
    val added = remember { mutableStateListOf<String>() }

    // Songs like the ones in the playlist (Spotify's radio of a few of them), shown before a search.
    LaunchedEffect(playlistUri) {
        val seeds = seedIds.filter { it.isNotBlank() }.shuffled().take(3)
        suggested = withContext(Dispatchers.IO) {
            seeds.flatMap { seed ->
                runCatching { com.music.spotui.di.Graph.repository.provideRecommendations(listOf(seed)) }.getOrDefault(emptyList())
            }.filter { it.spotifyTrackId.isNotBlank() && it.spotifyTrackId !in inPlaylist }
                .distinctBy { it.spotifyTrackId }
                .shuffled().take(30)
                .map { Candidate(it.spotifyTrackId, it.title, it.singer, it.coverUri) }
        }
    }
    // Search as the user types (a short pause first).
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { results = null; return@LaunchedEffect }
        delay(250)
        results = withContext(Dispatchers.IO) {
            runCatching { MyspotiRuntime.api.search.tracks(q, 0, 30).items }.getOrDefault(emptyList())
                .filter { it.uri.startsWith("spotify:track:") }
                .map { t ->
                    Candidate(
                        id = t.uri.substringAfterLast(':'),
                        title = t.name,
                        artists = t.artists.joinToString(", ") { it.name },
                        cover = t.album?.images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url.orEmpty(),
                    )
                }
        }
    }

    fun add(c: Candidate) {
        if (c.id in added) return
        added += c.id
        com.music.spotui.data.api.SpotifySync.addTrackToPlaylist(context, playlistId, c.id) { ok ->
            if (!ok) {
                added -= c.id
                android.widget.Toast.makeText(context, context.getString(R.string.playlist_change_refused), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF121212),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().imePadding()) {
            Text("Add to $playlistName", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 12.dp))
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search songs", color = Color(0xFF9A9A9A)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFFB3B3B3)) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF2A2A2A), unfocusedContainerColor = Color(0xFF2A2A2A),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = AppAccent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            val searching = query.isNotBlank()
            val list = if (searching) results else suggested
            Text(if (searching) "Results" else "Recommended", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 4.dp))
            if (list == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppAccent, modifier = Modifier.size(28.dp))
                }
            } else if (list.isEmpty()) {
                Text(if (searching) "No songs found" else "No suggestions yet: search for a song above",
                    color = Color(0xFFB3B3B3), fontSize = 14.sp, modifier = Modifier.padding(20.dp, 8.dp))
            } else LazyColumn(Modifier.fillMaxWidth()) {
                items(list, key = { it.id }) { c ->
                    val done = c.id in added || c.id in inPlaylist
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !done) { add(c) }.padding(16.dp, 8.dp),
                    ) {
                        GlideImage(
                            model = c.cover, contentDescription = null, transition = CrossFade,
                            contentScale = ContentScale.Crop,
                            failure = placeholder(R.drawable.placeholder), loading = placeholder(R.drawable.placeholder),
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF282828)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.artists, color = Color(0xFFB3B3B3), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(
                            if (done) Icons.Filled.Check else Icons.Outlined.AddCircleOutline,
                            contentDescription = if (done) "Added" else "Add",
                            tint = if (done) AppAccent else Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        }
    }
}
