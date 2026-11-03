package com.music.spotui.di

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One equaliser and bass boost for the whole app (Settings > Audio): a preset or the user's own
 * curve, plus bass boost. Always on the phone's own player (saved and downloaded songs); Spotify's
 * streams play in the hidden Web Player, whose sound the app cannot reach, so for them the effect
 * is put on the phone's whole sound output (audio session 0), which some phones refuse.
 */
object AudioFx {
    /** A preset: gains in dB at the five reference bands [BANDS_HZ]. */
    data class Preset(val name: String, val gains: List<Float>)

    /** The reference bands the presets and the custom curve are set at; mapped onto the phone's own bands. */
    val BANDS_HZ = listOf(60, 230, 910, 3_600, 14_000)

    val PRESETS = listOf(
        Preset("Flat", listOf(0f, 0f, 0f, 0f, 0f)),
        Preset("Bass", listOf(7f, 4f, 0f, 0f, 0f)),
        Preset("Car", listOf(5f, 2f, -1f, 2f, 4f)),
        Preset("Hip-hop", listOf(5f, 3f, -1f, 1f, 3f)),
        Preset("Pop", listOf(-1f, 1f, 3f, 2f, 1f)),
        Preset("Rock", listOf(4f, 2f, -1f, 3f, 4f)),
        Preset("Electronic", listOf(5f, 2f, 0f, 2f, 4f)),
        Preset("Vocal", listOf(-2f, -1f, 3f, 3f, 1f)),
        Preset("Acoustic", listOf(2f, 1f, 1f, 2f, 2f)),
        Preset("Classical", listOf(3f, 1f, -1f, 2f, 3f)),
        Preset("Treble", listOf(0f, 0f, 0f, 3f, 6f)),
    )
    const val CUSTOM = "Custom"
    const val MAX_DB = 12f

    private fun prefs() = Graph.app.getSharedPreferences("audio_fx", Context.MODE_PRIVATE)

    var enabled by mutableStateOf(prefs().getBoolean("enabled", false))
        private set
    /** A [PRESETS] name, or [CUSTOM]. */
    var preset by mutableStateOf(prefs().getString("preset", "Flat") ?: "Flat")
        private set
    var custom by mutableStateOf(
        prefs().getString("custom", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 5 } ?: List(5) { 0f }
    )
        private set
    /** Bass boost, 0..100 (%). */
    var bass by mutableStateOf(prefs().getInt("bass", 0))
        private set
    /** Also on Spotify's streams, through the phone's whole sound output. */
    var streaming by mutableStateOf(prefs().getBoolean("streaming", true))
        private set
    /** What the phone said to the whole-output effect: null untried, true working, false refused. */
    var streamingWorks by mutableStateOf<Boolean?>(null)
        private set

    /** The gains in use now. */
    val gains: List<Float> get() = if (preset == CUSTOM) custom else PRESETS.firstOrNull { it.name == preset }?.gains ?: List(5) { 0f }

    fun switchOn(on: Boolean) { enabled = on; prefs().edit().putBoolean("enabled", on).apply(); apply() }
    fun choose(name: String) { preset = name; prefs().edit().putString("preset", name).apply(); apply() }
    fun setBand(index: Int, db: Float) {
        // Moving a band starts from what is heard now, and the curve becomes the user's own.
        val base = gains.toMutableList()
        base[index] = db.coerceIn(-MAX_DB, MAX_DB)
        custom = base
        prefs().edit().putString("custom", base.joinToString(",")).apply()
        if (preset != CUSTOM) { preset = CUSTOM; prefs().edit().putString("preset", CUSTOM).apply() }
        apply()
    }
    fun boostBass(percent: Int) { bass = percent.coerceIn(0, 100); prefs().edit().putInt("bass", bass).apply(); apply() }
    fun switchStreaming(on: Boolean) {
        streaming = on
        prefs().edit().putBoolean("streaming", on).apply()
        if (on) attach(GLOBAL_SESSION) else release(GLOBAL_SESSION)
        apply()
    }

    // ── Effects, per audio session ──
    private class Fx(val eq: Equalizer?, val bass: BassBoost?)
    private val sessions = HashMap<Int, Fx>()
    private const val GLOBAL_SESSION = 0

    /** Called at start: the whole-output effect, when it is wanted. */
    fun init() { if (streaming) attach(GLOBAL_SESSION) }

    /** The phone's own player opened [sessionId] (saved and downloaded songs). */
    fun attachSession(sessionId: Int) { if (sessionId > 0) attach(sessionId) }

    @Synchronized private fun attach(session: Int) {
        if (sessions.containsKey(session)) { apply(); return }
        val eq = runCatching { Equalizer(0, session) }.getOrNull()
        val bb = runCatching { BassBoost(0, session) }.getOrNull()
        if (session == GLOBAL_SESSION) {
            streamingWorks = eq != null
            app.myspoti.host.android.Logbook.log(if (eq != null) "audio: equaliser on the whole sound output" else "audio: whole-output equaliser refused by this phone")
        }
        if (eq == null && bb == null) return
        sessions[session] = Fx(eq, bb)
        apply()
    }

    @Synchronized private fun release(session: Int) {
        sessions.remove(session)?.let { fx ->
            runCatching { fx.eq?.release() }
            runCatching { fx.bass?.release() }
        }
    }

    /** Puts the current settings on every attached session. */
    @Synchronized fun apply() {
        val g = gains
        for (fx in sessions.values) {
            fx.eq?.let { eq ->
                runCatching {
                    val range = eq.bandLevelRange // millibels [min, max]
                    for (b in 0 until eq.numberOfBands) {
                        val hz = eq.getCenterFreq(b.toShort()) / 1000f
                        val mb = (gainAt(hz, g) * 100f).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                        eq.setBandLevel(b.toShort(), mb.toShort())
                    }
                    eq.enabled = enabled
                }
            }
            fx.bass?.let { bb ->
                runCatching {
                    if (bb.strengthSupported) bb.setStrength((bass * 10).toShort())
                    bb.enabled = enabled && bass > 0
                }
            }
        }
    }

    /** The gain at [hz] on the curve through the reference bands (straight lines on a log scale). */
    private fun gainAt(hz: Float, g: List<Float>): Float {
        val x = kotlin.math.ln(hz.coerceAtLeast(1f))
        val xs = BANDS_HZ.map { kotlin.math.ln(it.toFloat()) }
        if (x <= xs.first()) return g.first()
        if (x >= xs.last()) return g.last()
        val i = xs.indexOfLast { it <= x }.coerceIn(0, xs.size - 2)
        val t = (x - xs[i]) / (xs[i + 1] - xs[i])
        return g[i] + (g[i + 1] - g[i]) * t
    }
}
