package dev.tubebeam.send

import dev.tubebeam.adb.AdbError
import dev.tubebeam.data.Settings
import dev.tubebeam.data.TvDevice
import dev.tubebeam.data.TvRepository
import dev.tubebeam.discovery.FindResult
import dev.tubebeam.discovery.TvFinder

/** What a send is doing right now, for UIs that want to show more than a spinner. */
sealed interface SendProgress {
    data object Sending : SendProgress
    /** The stored address didn't answer; looking for the TV elsewhere on the network. */
    data object Searching : SendProgress
    /** Found at [host]; retrying the send there. */
    data class Relocated(val host: String) : SendProgress
}

/**
 * Sends to a saved TV honouring settings, records success, and heals a changed IP:
 * if the stored address is unreachable and the TV's identity is known, it is searched for
 * on the LAN and the send retried at the new address, which is then saved.
 */
class SendCoordinator(
    private val sender: VideoSender,
    private val repo: TvRepository,
    private val currentSettings: suspend () -> Settings,
    private val finder: TvFinder? = null,
) {
    suspend fun send(tv: TvDevice, url: String, onProgress: (SendProgress) -> Unit = {}): SendResult {
        onProgress(SendProgress.Sending)
        val wake = currentSettings().wakeTv
        var device = tv
        var result = attempt(device, url, wake)

        if (result.isUnreachable() && finder != null && device.serial != null) {
            onProgress(SendProgress.Searching)
            val found = finder.find(device)
            if (found is FindResult.Found) {
                onProgress(SendProgress.Relocated(found.host))
                device = device.copy(host = found.host)
                result = attempt(device, url, wake)
                if (result is SendResult.Success) repo.updateHost(device.id, found.host)
            }
        }

        if (result is SendResult.Success) {
            repo.markSeen(device.id)
            if (device.serial == null) repo.rememberSerial(device.id, result.identity)
        }
        return result
    }

    private suspend fun attempt(tv: TvDevice, url: String, wake: Boolean): SendResult =
        sender.send(SendTarget(tv.host, tv.port, tv.targetPackage), url, wakeTv = wake, readIdentity = tv.serial == null)

    private fun SendResult.isUnreachable(): Boolean =
        this is SendResult.Failure && (error is AdbError.Unreachable || error is AdbError.Timeout)
}
