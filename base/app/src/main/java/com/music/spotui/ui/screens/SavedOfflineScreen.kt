package com.music.spotui.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.cache.PlaybackCacheManager
import com.music.spotui.data.cache.PlaybackCacheStore
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground

/** The library entry's name and the local player's context name of this list. */
const val SAVED_OFFLINE_TITLE = "Saved for offline"

/** How the list is ordered; [CUSTOM] is the user's own order (moved by hand), kept on the phone. */
enum class SavedOrder(val label: String) { RECENT("Recently played"), ADDED("Recently saved"), TITLE("Title"), ARTIST("Artist"), CUSTOM("Custom") }

/** The list's order and the custom order, kept in preferences. */
object SavedOfflinePrefs {
    private fun prefs() = com.music.spotui.di.Graph.app.getSharedPreferences("saved_offline", android.content.Context.MODE_PRIVATE)
    var order by mutableStateOf(runCatching { SavedOrder.valueOf(prefs().getString("order", null) ?: "RECENT") }.getOrDefault(SavedOrder.RECENT))
        private set
    /** Track ids in the custom order (songs saved since come first, newest on top). */
    var custom by mutableStateOf(prefs().getString("custom", "").orEmpty().split(',').filter { it.isNotBlank() })
        private set

    fun choose(o: SavedOrder) { order = o; prefs().edit().putString("order", o.name).apply() }
    fun saveCustom(ids: List<String>) { custom = ids; prefs().edit().putString("custom", ids.joinToString(",")).apply() }
}

/**
 * "Saved for offline": every song the app keeps on the phone by itself (the song playing, the
 * 2 hours ahead, songs played, the first songs of playlists opened; up to 500), most recently
 * played first. Plays from the phone, signal or not.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SavedOfflineScreen(navController: NavController) {
    val context = LocalContext.current
    // Snapshot read: the list follows songs as they are saved.
    val all = PlaybackCacheStore.cachedTracks.values.toList()
    val order = SavedOfflinePrefs.order
    val tracks = when (order) {
        SavedOrder.RECENT -> all.sortedByDescending { it.lastPlayedAt }
        SavedOrder.ADDED -> all.sortedByDescending { it.cachedAt }
        SavedOrder.TITLE -> all.sortedBy { it.title.lowercase() }
        SavedOrder.ARTIST -> all.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
        SavedOrder.CUSTOM -> {
            val pos = SavedOfflinePrefs.custom.withIndex().associate { (i, id) -> id to i }
            val (placed, fresh) = all.partition { it.trackId in pos }
            fresh.sortedByDescending { it.cachedAt } + placed.sortedBy { pos.getValue(it.trackId) }
        }
    }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }

    /** Moves [id] to [to] in the list as shown, switching the list to the custom order. */
    fun move(id: String, to: Int) {
        val ids = tracks.map { it.trackId }.toMutableList()
        val from = ids.indexOf(id)
        if (from < 0) return
        ids.removeAt(from)
        ids.add(to.coerceIn(0, ids.size), id)
        SavedOfflinePrefs.saveCustom(ids)
        SavedOfflinePrefs.choose(SavedOrder.CUSTOM)
    }
    fun remove(id: String) {
        PlaybackCacheStore.removeTrack(id)
        SavedOfflinePrefs.saveCustom(SavedOfflinePrefs.custom - id)
    }
    if (clearing) AlertDialog(
        onDismissRequest = { clearing = false },
        containerColor = Color(0xFF282828),
        title = { Text("Remove all saved songs?", color = Color.White) },
        text = { Text("${tracks.size} songs are deleted from this phone. They are saved again as you play.", color = Color(0xFFB3B3B3)) },
        confirmButton = { TextButton(onClick = { clearing = false; PlaybackCacheStore.clearAll(); SavedOfflinePrefs.saveCustom(emptyList()) }) { Text("Remove all", color = Color(0xFFF15E6C)) } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel", color = Color.White) } },
    )
    val np by SongPlayer.nowPlaying
    val playingHere = LocalPlayer.active.value && LocalPlayer.contextName == SAVED_OFFLINE_TITLE

    fun play(index: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        LocalPlayer.play(tracks.map(PlaybackCacheStore::toLocalTrack), index, shuffle = shuffle, context = SAVED_OFFLINE_TITLE)
    }

    Box(Modifier.fillMaxSize().background(AppBackground)) {
        if (tracks.isEmpty()) Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        ) {
            Image(painterResource(R.drawable.downloads_cover), null, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(6.dp)))
            Text("Nothing saved yet", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
            Text("Songs you play, the ones coming up, and the first songs of playlists you open are kept here (up to ${PlaybackCacheManager.MAX_SONGS}).",
                color = Color(0xFFB3B3B3), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        } else LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF1F3A93), AppBackground)))
                        .statusBarsPadding().padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(56.dp))
                    Image(
                        painterResource(R.drawable.downloads_cover), null,
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(200.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Text(SAVED_OFFLINE_TITLE, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
                    val size = Formatter.formatShortFileSize(context, PlaybackCacheStore.totalBytes())
                    Text("${tracks.size} of ${PlaybackCacheManager.MAX_SONGS} songs · $size · plays with no signal",
                        color = Color(0xFFB3B3B3), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SavedOrder.entries.forEach { o ->
                            val on = o == order
                            Text(o.label, color = if (on) Color.Black else Color.White, fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) AppAccent else Color(0xFF2A2A2A))
                                    .clickable { SavedOfflinePrefs.choose(o) }.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Remove all", color = Color(0xFFB3B3B3), fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { clearing = true }.padding(horizontal = 4.dp, vertical = 6.dp))
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Filled.Shuffle, "Shuffle", tint = Color.White,
                            modifier = Modifier.size(40.dp).clip(CircleShape).clickable { play(0, shuffle = true) }.padding(8.dp))
                        Spacer(Modifier.width(12.dp))
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(AppAccent).clickable { play(0) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.PlayArrow, "Play", tint = Color.Black, modifier = Modifier.size(30.dp)) }
                    }
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.trackId }) { i, t ->
                val current = (playingHere && LocalPlayer.current()?.title == t.title) || np.uri == "spotify:track:${t.trackId}"
                // Swipe to delete from the phone; ⋮ for the same and to move it in the list.
                com.music.spotui.ui.components.SwipeToRemove(key = t.trackId, onRemove = { remove(t.trackId) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(AppBackground).clickable { play(i) }.padding(16.dp, 8.dp),
                ) {
                    GlideImage(
                        model = t.coverUrl, contentDescription = null, transition = CrossFade, contentScale = ContentScale.Crop,
                        failure = placeholder(R.drawable.placeholder), loading = placeholder(R.drawable.placeholder),
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF282828)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = if (current) AppAccent else Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, color = Color(0xFFB3B3B3), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        Icon(Icons.Filled.MoreVert, "More", tint = Color(0xFFB3B3B3),
                            modifier = Modifier.size(40.dp).clip(CircleShape).clickable { menuFor = t.trackId }.padding(8.dp))
                        DropdownMenu(expanded = menuFor == t.trackId, onDismissRequest = { menuFor = null }, containerColor = Color(0xFF282828)) {
                            DropdownMenuItem(text = { Text("Move to top", color = Color.White) }, onClick = { menuFor = null; move(t.trackId, 0) })
                            if (i > 0) DropdownMenuItem(text = { Text("Move up", color = Color.White) }, onClick = { menuFor = null; move(t.trackId, i - 1) })
                            if (i < tracks.lastIndex) DropdownMenuItem(text = { Text("Move down", color = Color.White) }, onClick = { menuFor = null; move(t.trackId, i + 1) })
                            DropdownMenuItem(text = { Text("Move to bottom", color = Color.White) }, onClick = { menuFor = null; move(t.trackId, tracks.lastIndex) })
                            DropdownMenuItem(text = { Text("Remove from phone", color = Color(0xFFF15E6C)) }, onClick = { menuFor = null; remove(t.trackId) })
                        }
                    }
                }
                }
            }
            item { Spacer(Modifier.height(160.dp)) }
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White,
            modifier = Modifier.statusBarsPadding().padding(8.dp).size(40.dp).clip(CircleShape)
                .clickable { navController.navigateUp() }.padding(8.dp),
        )
    }
}
