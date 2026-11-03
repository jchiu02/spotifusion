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
    val tracks = PlaybackCacheStore.cachedTracks.values.sortedByDescending { it.lastPlayedAt }
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
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { play(i) }.padding(16.dp, 8.dp),
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
