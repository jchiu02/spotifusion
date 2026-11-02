package app.myspoti

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.TextView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.io.File

/**
 * Step 1 of the Firefox player: Spotify's Web Player shown in Firefox's engine (GeckoView) with
 * Widevine on, to find out whether it plays on this phone before the hidden player is moved over.
 * Visible on purpose: the user signs in and plays a song by hand.
 */
class GeckoTestActivity : Activity() {
    companion object {
        private const val TAG = "GeckoTest"
        // One Gecko runtime per process.
        @Volatile private var runtime: GeckoRuntime? = null

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

        fun runtime(activity: Activity): GeckoRuntime = runtime ?: synchronized(this) {
            runtime ?: run {
                // Widevine and EME are off by default in GeckoView: turned on through its config
                // file (read in debuggable builds, which this one is).
                val config = File(activity.filesDir, "geckoview-config.yaml")
                config.writeText(
                    """
                    prefs:
                      media.eme.enabled: true
                      media.mediadrm-widevinecdm.visible: true
                      media.eme.require-app-approval: false
                      media.autoplay.default: 0
                      media.autoplay.blocking_policy: 0
                    """.trimIndent() + "\n"
                )
                val settings = GeckoRuntimeSettings.Builder()
                    .configFilePath(config.absolutePath)
                    .consoleOutput(true)
                    .build()
                GeckoRuntime.create(activity.applicationContext, settings).also { runtime = it }
            }
        }
    }

    private var session: GeckoSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        val view = GeckoView(this)
        val status = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xAA000000.toInt())
            textSize = 12f
            setPadding(16, 8, 16, 8)
            text = "Firefox engine test: loading…"
        }
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        val s = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                .userAgentOverride(DESKTOP_UA)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                .build()
        )
        s.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> {
                Log.i(TAG, "permission asked: ${perm.permission}: allowed")
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                status.text = if (success) "Firefox engine test: page loaded. Sign in, then play a song." else "Firefox engine test: page failed to load"
            }
        }
        s.mediaSessionDelegate = object : org.mozilla.geckoview.MediaSession.Delegate {
            override fun onPlay(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
                status.text = "Firefox engine test: PLAYING. If you hear it, the Firefox player works on this phone."
            }
            override fun onPause(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
                status.text = "Firefox engine test: paused"
            }
        }
        s.open(runtime(this))
        view.setSession(s)
        s.loadUri("https://open.spotify.com/")
        session = s
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }
}
