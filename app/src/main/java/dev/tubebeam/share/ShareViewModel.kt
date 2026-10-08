package dev.tubebeam.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tubebeam.AppContainer
import dev.tubebeam.adb.AdbError
import dev.tubebeam.data.TvDevice
import dev.tubebeam.send.SendResult
import dev.tubebeam.youtube.TargetApp
import dev.tubebeam.youtube.YouTubeLinkParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface ShareUi {
    data object Loading : ShareUi
    data object NotYouTube : ShareUi
    data object NoTvs : ShareUi
    data class Pick(val tvs: List<TvDevice>, val defaultId: Long?) : ShareUi
    data class Sending(val tv: TvDevice) : ShareUi
    data class Sent(val tv: TvDevice, val packageUsed: String?) : ShareUi
    data class Error(val tv: TvDevice, val message: String, val hint: String?) : ShareUi
}

class ShareViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow<ShareUi>(ShareUi.Loading)
    val ui: StateFlow<ShareUi> = _ui

    private var url: String? = null
    private var started = false

    /** Called once with the shared text. */
    fun start(sharedText: String?) {
        if (started) return
        started = true
        val link = YouTubeLinkParser.parse(sharedText)
        if (link == null) {
            _ui.value = ShareUi.NotYouTube
            return
        }
        url = link.canonicalUrl
        viewModelScope.launch {
            val tvs = container.repo.tvs.first()
            val settings = container.settings.current()
            val default = tvs.firstOrNull { it.isDefault }
            when {
                tvs.isEmpty() -> _ui.value = ShareUi.NoTvs
                tvs.size == 1 -> send(tvs[0])
                default != null && settings.alwaysUseDefault -> send(default)
                else -> _ui.value = ShareUi.Pick(tvs, default?.id)
            }
        }
    }

    fun choose(tv: TvDevice, remember: Boolean) {
        viewModelScope.launch {
            if (remember) {
                container.repo.setDefault(tv.id)
                container.settings.setAlwaysUseDefault(true)
            }
            send(tv)
        }
    }

    fun retry() {
        val tv = (_ui.value as? ShareUi.Error)?.tv ?: return
        viewModelScope.launch { send(tv) }
    }

    private suspend fun send(tv: TvDevice) {
        val target = url ?: return
        _ui.value = ShareUi.Sending(tv)
        _ui.value = when (val r = container.sendCoordinator.send(tv, target)) {
            is SendResult.Success -> ShareUi.Sent(tv, r.packageUsed)
            is SendResult.NoPlayerApp -> ShareUi.Error(tv, "SmartTube isn't installed on ${tv.name}.", null)
            is SendResult.Failure -> ShareUi.Error(tv, friendlyMessage(tv, r.error), hint(r.error))
        }
    }

    private fun friendlyMessage(tv: TvDevice, e: AdbError): String = when (e) {
        is AdbError.Unreachable -> "Can't reach ${tv.name}. Is it on and on the same Wi-Fi?"
        is AdbError.Refused -> "${tv.name} refused the connection. Network debugging seems off on the TV."
        is AdbError.Unauthorized -> "${tv.name} hasn't trusted this phone. Open TubeBeam to reconnect."
        is AdbError.Timeout -> "${tv.name} didn't answer in time."
        is AdbError.Unknown -> "Sending to ${tv.name} failed: ${e.message}"
    }

    private fun hint(e: AdbError): String? = when (e) {
        is AdbError.Refused -> "Some TVs turn debugging off after a reboot or update."
        is AdbError.Unauthorized -> "Open the TV in TubeBeam and tap Reconnect to get the prompt again."
        else -> null
    }

    companion object {
        fun describePackage(pkg: String?): String = TargetApp.displayName(pkg)
    }
}
