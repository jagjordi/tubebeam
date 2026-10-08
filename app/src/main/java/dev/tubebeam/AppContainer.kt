package dev.tubebeam

import android.content.Context
import dev.tubebeam.adb.AdbKeyStore
import dev.tubebeam.adb.AdbTransport
import dev.tubebeam.adb.KadbTransport
import dev.tubebeam.data.AppDatabase
import dev.tubebeam.data.SettingsStore
import dev.tubebeam.data.TvRepository
import dev.tubebeam.send.SendCoordinator
import dev.tubebeam.send.VideoSender

/** Manual dependency wiring. One instance per process, owned by [TubeBeamApp]. */
class AppContainer(context: Context) {
    val keyStore: AdbKeyStore = AdbKeyStore(context.filesDir)
    val adb: AdbTransport = KadbTransport(keyStore)
    val sender: VideoSender = VideoSender(adb)
    val db: AppDatabase by lazy { AppDatabase.build(context) }
    val repo: TvRepository by lazy { TvRepository(db.tvDao()) }
    val settings: SettingsStore = SettingsStore(context)
    val sendCoordinator: SendCoordinator by lazy { SendCoordinator(sender, repo, settings) }
}
