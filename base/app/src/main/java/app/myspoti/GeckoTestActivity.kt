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
        // A TextureView, not the default SurfaceView: it draws inside the window like any view,
        // so the status lines over it always show (a SurfaceView left only a white screen).
        runCatching { view.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW) }
        val status = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xEE000000.toInt())
            textSize = 12f
            setPadding(24, 48, 24, 16)
            elevation = 100f
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val lines = ArrayList<String>()
        fun say(line: String) = runOnUiThread {
            val t = (android.os.SystemClock.elapsedRealtime() - started) / 1000
            lines += "[${t}s] $line"
            while (lines.size > 12) lines.removeAt(0)
            status.text = "Firefox engine test\n" + lines.joinToString("\n")
            Log.i(TAG, line)
        }
        say("starting Firefox engine…")
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        status.bringToFront()
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
            override fun onPageStart(session: GeckoSession, url: String) { say("page start: ${url.take(60)}") }
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (progress % 25 == 0 || progress == 100) say("loading $progress%")
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                say(if (success) "page loaded. Sign in, then play a song." else "page FAILED to load")
            }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFirstComposite(session: GeckoSession) { say("first frame drawn") }
            override fun onFirstContentfulPaint(session: GeckoSession) { say("page content drawn") }
            override fun onCrash(session: GeckoSession) { say("CONTENT PROCESS CRASHED") }
            override fun onKill(session: GeckoSession) { say("content process killed by Android") }
        }
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadError(session: GeckoSession, uri: String?, error: org.mozilla.geckoview.WebRequestError): GeckoResult<String>? {
                say("LOAD ERROR category=${error.category} code=${error.code} on ${uri?.take(60)}")
                return null
            }
        }
        s.mediaSessionDelegate = object : org.mozilla.geckoview.MediaSession.Delegate {
            override fun onPlay(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
                say("PLAYING. If you hear it, the Firefox player works on this phone.")
            }
            override fun onPause(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
                say("paused")
            }
        }
        val rt = try { runtime(this) } catch (t: Throwable) {
            say("engine failed to start: ${t.javaClass.simpleName}: ${t.message?.take(120)}")
            return
        }
        say("engine started (${android.os.Build.SUPPORTED_ABIS.firstOrNull()})")
        s.open(rt)
        view.setSession(s)
        s.loadUri("https://open.spotify.com/")
        say("opening open.spotify.com…")
        session = s
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }
}
