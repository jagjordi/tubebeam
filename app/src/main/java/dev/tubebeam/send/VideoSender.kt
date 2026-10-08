package dev.tubebeam.send

import dev.tubebeam.adb.AdbError
import dev.tubebeam.adb.AdbTransport
import dev.tubebeam.youtube.TargetApp

/** Where to send: enough of a TvDevice to open a connection. */
data class SendTarget(
    val host: String,
    val port: Int,
    /** Package to launch; null means detect on the TV at send time. */
    val targetPackage: String? = null,
)

sealed interface SendResult {
    data class Success(val durationMs: Long, val packageUsed: String?) : SendResult
    data class Failure(val error: AdbError, val durationMs: Long) : SendResult
    /** Connected fine, but neither SmartTube nor the YouTube app is installed on the TV. */
    data class NoPlayerApp(val durationMs: Long) : SendResult
}

/**
 * Opens a fresh ADB connection, wakes the TV, fires a VIEW intent for [url], closes.
 * No sockets are kept between sends; a send is expected to take ~1 s on a healthy LAN.
 */
class VideoSender(private val adb: AdbTransport) {

    suspend fun send(target: SendTarget, url: String, wakeTv: Boolean = true): SendResult {
        val started = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - started
        return try {
            adb.connect(target.host, target.port, connectTimeoutMs = CONNECT_TIMEOUT_MS, ioTimeoutMs = SHELL_TIMEOUT_MS).use { s ->
                val pkg = target.targetPackage
                    ?: TargetApp.pickFrom(s.shell(TargetApp.DETECT_COMMAND).stdout)
                    // Without a player the TV's stub handler just toasts "You don't have an app that can do this".
                    ?: return SendResult.NoPlayerApp(elapsed())

                val first = s.shell(buildCommand(url, pkg, wakeTv))
                var used: String? = pkg
                if (looksLikeFailure(first.allOutput)) {
                    // The stored package may be gone or no longer handle youtube.com; let the TV choose.
                    val second = s.shell(buildCommand(url, null, wake = false))
                    if (looksLikeFailure(second.allOutput)) {
                        throw AdbError.Unknown(IllegalStateException(second.allOutput.trim().ifEmpty { "am start failed" }))
                    }
                    used = null
                }
                SendResult.Success(elapsed(), used)
            }
        } catch (e: AdbError) {
            SendResult.Failure(e, elapsed())
        } catch (e: Exception) {
            SendResult.Failure(AdbError.Unknown(e), elapsed())
        }
    }

    /** Detects the best target package on the TV, or null if none of the known apps is installed. */
    suspend fun detectPackage(host: String, port: Int, ioTimeoutMs: Int = SHELL_TIMEOUT_MS): String? =
        adb.connect(host, port, connectTimeoutMs = CONNECT_TIMEOUT_MS, ioTimeoutMs = ioTimeoutMs).use { s ->
            TargetApp.pickFrom(s.shell(TargetApp.DETECT_COMMAND).stdout)
        }

    companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val SHELL_TIMEOUT_MS = 5_000
        private const val WAKE = "input keyevent KEYCODE_WAKEUP"

        internal fun buildCommand(url: String, pkg: String?, wake: Boolean): String = buildString {
            if (wake) append(WAKE).append("; ")
            append("am start -a android.intent.action.VIEW -d ").append(shellQuote(url))
            if (pkg != null) append(" -p ").append(pkg)
        }

        /** `am start` exits 0 even when it fails; the failure is only in its output. */
        internal fun looksLikeFailure(output: String): Boolean {
            val o = output.lowercase()
            return "error" in o || "unable to resolve" in o || "exception" in o || "does not exist" in o
        }

        /** Single-quotes for the TV's shell; the URL contains `&`. */
        internal fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
