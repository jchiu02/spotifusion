package com.music.spotui.data

import com.music.spotui.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Whether the internet really answers. Android's own "connected" says a network is attached, and
 * on the Tube it stays attached with nothing behind it (station Wi-Fi without internet, a mobile
 * signal that carries no data): the app took itself for online and none of its offline handling
 * started (reported: songs picked in a tunnel tried to stream, silence). A tiny request is sent
 * every few seconds while music plays, and at once when something hints at a loss; two misses in
 * a row make the app offline, one answer makes it online again.
 */
object NetProbe {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        OkHttpClient.Builder().callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
    }
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** The internet answered at the last probes (true until proven otherwise). */
    @Volatile var reachable = true
        private set
    @Volatile private var misses = 0
    @Volatile private var started = false
    @Volatile private var checking = false

    /** Online for real: a network attached and the internet answering. */
    fun online(context: android.content.Context = Graph.app): Boolean =
        com.music.spotui.data.offline.OfflineStore.isOnline(context) && reachable

    /** Called with the new value when [reachable] changes. */
    fun onChange(listener: (Boolean) -> Unit) { listeners += listener }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                // Never takes the app down: a failed probe is only a missed answer.
                runCatching { check() }
                // Often while music plays (a tunnel must be noticed in seconds), rarely otherwise.
                // Asked on the main thread: the phone's own player (ExoPlayer) may only be read
                // there, and reading it from here crashed the app once a saved song had played.
                val playing = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { com.music.spotui.di.SongPlayer.isPlaying() }
                }.getOrDefault(false)
                delay(if (playing) PLAYING_EVERY_MS else IDLE_EVERY_MS)
            }
        }
    }

    /** Something hints at a loss (the network changed, a request failed, the sound stopped): probe now. */
    fun poke() { scope.launch { runCatching { check() } } }

    private suspend fun check() {
        if (checking) return
        checking = true
        try {
            val ok = com.music.spotui.data.offline.OfflineStore.isOnline(Graph.app) && answers()
            if (ok) {
                misses = 0
                set(true)
            } else {
                misses++
                if (misses >= MISSES_FOR_OFFLINE) set(false)
                else { delay(RECHECK_MS); checking = false; check() }
            }
        } finally {
            checking = false
        }
    }

    private fun answers(): Boolean = PROBE_URLS.any { url ->
        runCatching {
            http.newCall(Request.Builder().url(url).head().build()).execute().use { it.code in 200..499 }
        }.getOrDefault(false)
    }

    private fun set(value: Boolean) {
        if (reachable == value) return
        reachable = value
        app.myspoti.host.android.Logbook.log(if (value) "internet answers again" else "internet not answering (network attached, no data): offline")
        listeners.forEach { l -> runCatching { l(value) } }
    }

    private val PROBE_URLS = listOf("https://www.gstatic.com/generate_204", "https://open.spotify.com/robots.txt")
    private const val PROBE_TIMEOUT_MS = 2_000L
    private const val RECHECK_MS = 1_000L
    private const val MISSES_FOR_OFFLINE = 2
    private const val PLAYING_EVERY_MS = 8_000L
    private const val IDLE_EVERY_MS = 60_000L
}
