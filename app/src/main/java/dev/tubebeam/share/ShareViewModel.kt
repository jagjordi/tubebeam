package dev.tubebeam.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tubebeam.AppContainer
import dev.tubebeam.adb.AdbError
import dev.tubebeam.adb.PortProbe
import dev.tubebeam.data.TvDevice
import dev.tubebeam.discovery.FindResult
import dev.tubebeam.send.SendProgress
import dev.tubebeam.send.SendResult
import dev.tubebeam.youtube.TargetApp
import dev.tubebeam.youtube.YouTubeLinkParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Per-row state in the picker: a quick TCP probe on open, and the outcome of a manual search. */
enum class RowStatus { UNKNOWN, ONLINE, OFFLINE, SEARCHING, NOT_FOUND }

sealed interface ShareUi {
    data object Loading : ShareUi
    data object NotYouTube : ShareUi
    data object NoTvs : ShareUi
    data class Pick(val tvs: List<TvDevice>, val defaultId: Long?, val status: Map<Long, RowStatus> = emptyMap()) : ShareUi
    data class Sending(val tv: TvDevice, val progress: SendProgress = SendProgress.Sending) : ShareUi
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
                else -> showPicker(tvs, default?.id)
            }
        }
    }

    private fun showPicker(tvs: List<TvDevice>, defaultId: Long?) {
        _ui.value = ShareUi.Pick(tvs, defaultId)
        // Quick liveness check per row so the user sees which TVs will answer before choosing.
        tvs.forEach { tv ->
            viewModelScope.launch {
                val up = container.adb.probe(tv.host, tv.port, timeoutMs = PROBE_TIMEOUT_MS) == PortProbe.OPEN
                setRowStatus(tv.id, if (up) RowStatus.ONLINE else RowStatus.OFFLINE, onlyIf = RowStatus.UNKNOWN)
            }
        }
    }

    private fun setRowStatus(id: Long, status: RowStatus, onlyIf: RowStatus? = null) {
        _ui.update { ui ->
            if (ui !is ShareUi.Pick) return@update ui
            val current = ui.status[id] ?: RowStatus.UNKNOWN
            if (onlyIf != null && current != onlyIf) ui else ui.copy(status = ui.status + (id to status))
        }
    }

    /** Manual "find this TV" from an offline row: searches the LAN by identity and updates the address. */
    fun locate(tv: TvDevice) {
        viewModelScope.launch {
            setRowStatus(tv.id, RowStatus.SEARCHING)
            when (val r = container.finder.find(tv)) {
                is FindResult.Found -> {
                    container.repo.updateHost(tv.id, r.host)
                    _ui.update { ui ->
                        if (ui !is ShareUi.Pick) ui
                        else ui.copy(tvs = ui.tvs.map { if (it.id == tv.id) it.copy(host = r.host) else it }, status = ui.status + (tv.id to RowStatus.ONLINE))
                    }
                }
                FindResult.NotFound, FindResult.NoIdentity -> setRowStatus(tv.id, RowStatus.NOT_FOUND)
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
        val result = container.sendCoordinator.send(tv, target) { p -> _ui.value = ShareUi.Sending(tv, p) }
        _ui.value = when (val r = result) {
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
        is AdbError.Unreachable, is AdbError.Timeout -> "Its address may have changed; I also looked for it on the network."
        is AdbError.Refused -> "Some TVs turn debugging off after a reboot or update."
        is AdbError.Unauthorized -> "Open the TV in TubeBeam and tap Reconnect to get the prompt again."
        else -> null
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 600
        fun describePackage(pkg: String?): String = TargetApp.displayName(pkg)
    }
}
