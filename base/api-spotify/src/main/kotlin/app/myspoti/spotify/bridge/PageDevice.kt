package app.myspoti.spotify.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the page's playback device has joined Spotify Connect since the page last loaded. A new
 * page says "ready" before that: a command sent then is refused (log seen: a play 0.3 s after
 * "ready", answered DEVICE_NOT_FOUND, the device's join coming 0.3 s later). The host reports the
 * join (the page's connect-state PUT answered); the player and the bridge report a page going away.
 */
object PageDevice {
    private val _joined = MutableStateFlow(false)
    /** The current page's device has joined. */
    val joined: StateFlow<Boolean> = _joined.asStateFlow()

    /** A join was reported once in this process: the host reports them, so [joined] can be waited for. */
    @Volatile var reported = false
        private set

    /** The page's device joined Spotify Connect (its connect-state PUT was answered 2xx). */
    fun joined() { reported = true; _joined.value = true }

    private val _lost = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The page's device, joined before, was dropped by Spotify (commands answered DEVICE_NOT_FOUND). */
    val lost: kotlinx.coroutines.flow.SharedFlow<Unit> = _lost

    /** A command to the page's device was answered DEVICE_NOT_FOUND. */
    fun notFound() {
        // Before the first join it is the start-up race, waited for elsewhere; after it, the
        // device is gone (seen after the app sat idle: every play 404, nothing played).
        if (_joined.value) { _joined.value = false; _lost.tryEmit(Unit) }
    }

    /** The page is going away (a reload asked for or started): its device is not one to command. */
    fun pageGone() { _joined.value = false }
}
