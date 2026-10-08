package dev.tubebeam.send

import dev.tubebeam.data.SettingsStore
import dev.tubebeam.data.TvDevice
import dev.tubebeam.data.TvRepository

/** Sends to a saved TV honouring settings and recording success. */
class SendCoordinator(
    private val sender: VideoSender,
    private val repo: TvRepository,
    private val settings: SettingsStore,
) {
    suspend fun send(tv: TvDevice, url: String): SendResult {
        val wake = settings.current().wakeTv
        val result = sender.send(SendTarget(tv.host, tv.port, tv.targetPackage), url, wakeTv = wake)
        if (result is SendResult.Success) repo.markSeen(tv.id)
        return result
    }
}
