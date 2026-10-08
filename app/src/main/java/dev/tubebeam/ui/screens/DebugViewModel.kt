package dev.tubebeam.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tubebeam.AppContainer
import dev.tubebeam.adb.AdbError
import dev.tubebeam.adb.AdbSession
import dev.tubebeam.adb.PortProbe
import dev.tubebeam.send.SendResult
import dev.tubebeam.send.SendTarget
import dev.tubebeam.youtube.TargetApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DebugUiState(
    val host: String = "",
    val port: String = "5555",
    val fingerprint: String = "…",
    val status: String = "Not connected",
    val busy: Boolean = false,
    val connected: Boolean = false,
    val command: String = "getprop ro.product.model",
    val output: String = "",
    val sendStatus: String = "",
)

/** Phase 1 proof of concept: connect to a TV, approve the key, run a shell command. */
class DebugViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DebugUiState())
    val state: StateFlow<DebugUiState> = _state

    private var session: AdbSession? = null

    init {
        viewModelScope.launch {
            val fp = withContext(Dispatchers.IO) {
                runCatching { container.keyStore.publicKeyFingerprintMd5() }
                    .getOrElse { "error: ${it.message}" }
            }
            _state.update { it.copy(fingerprint = fp) }
        }
    }

    fun onHostChange(v: String) = _state.update { it.copy(host = v) }
    fun onPortChange(v: String) = _state.update { it.copy(port = v.filter(Char::isDigit)) }
    fun onCommandChange(v: String) = _state.update { it.copy(command = v) }

    fun connect() {
        val s = _state.value
        val host = s.host.trim()
        val port = s.port.toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            _state.update { it.copy(status = "Enter a valid IP and port") }
            return
        }
        viewModelScope.launch {
            disconnectInternal()
            _state.update { it.copy(busy = true, status = "Checking $host:$port…") }
            when (container.adb.probe(host, port)) {
                PortProbe.REFUSED -> {
                    _state.update { it.copy(busy = false, status = "Port $port refused. Is network debugging on?") }
                    return@launch
                }
                PortProbe.UNREACHABLE -> {
                    _state.update { it.copy(busy = false, status = "Can't reach $host. Same Wi-Fi? TV on?") }
                    return@launch
                }
                PortProbe.OPEN -> Unit
            }
            _state.update {
                it.copy(status = "Connecting. If the TV asks, tick \"Always allow\" and press OK (waiting up to 60 s)…")
            }
            try {
                session = container.adb.connect(host, port, connectTimeoutMs = 4_000, ioTimeoutMs = 60_000)
                _state.update { it.copy(busy = false, connected = true, status = "Connected to $host:$port") }
            } catch (e: AdbError) {
                _state.update { it.copy(busy = false, connected = false, status = describe(e)) }
            }
        }
    }

    fun runShell() {
        val cmd = _state.value.command.trim()
        if (cmd.isEmpty()) return
        viewModelScope.launch {
            val current = session
            if (current == null) {
                _state.update { it.copy(status = "Connect first") }
                return@launch
            }
            _state.update { it.copy(busy = true) }
            val started = System.currentTimeMillis()
            try {
                val r = current.shell(cmd)
                val ms = System.currentTimeMillis() - started
                _state.update {
                    it.copy(busy = false, output = "\$ $cmd\n${r.allOutput}(exit ${r.exitCode}, ${ms} ms)")
                }
            } catch (e: AdbError) {
                _state.update { it.copy(busy = false, connected = false, status = describe(e), output = "") }
                disconnectInternal()
            }
        }
    }

    /** Phase 2 check: send a hard-coded video with a fresh connection, like the share flow will. */
    fun sendTestVideo() {
        val s = _state.value
        val host = s.host.trim()
        val port = s.port.toIntOrNull()
        if (host.isEmpty() || port == null) {
            _state.update { it.copy(sendStatus = "Enter a valid IP and port") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, sendStatus = "Sending test video…") }
            val result = container.sender.send(SendTarget(host, port, targetPackage = null), TEST_VIDEO_URL)
            val text = when (result) {
                is SendResult.Success ->
                    "Sent in ${result.durationMs} ms via ${TargetApp.displayName(result.packageUsed)}"
                is SendResult.Failure -> "${describe(result.error)} (${result.durationMs} ms)"
                is SendResult.NoPlayerApp -> "No SmartTube or YouTube app on this TV. Install SmartTube first."
            }
            _state.update { it.copy(busy = false, sendStatus = text) }
        }
    }

    /** Shows every installed package that could be a player, to debug detection on a TV. */
    fun listPlayerApps() {
        val s = _state.value
        val host = s.host.trim()
        val port = s.port.toIntOrNull() ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, sendStatus = "Listing packages…") }
            val text = try {
                container.adb.connect(host, port).use { session ->
                    val out = session.shell(TargetApp.DETECT_COMMAND)
                    val all = out.stdout.lineSequence().count { it.startsWith("package:") }
                    val c = TargetApp.candidatesFrom(out.stdout)
                    if (c.isEmpty()) "$all packages installed, none look like SmartTube/YouTube.\n" +
                        "stderr: ${out.stderr.trim()} exit ${out.exitCode}"
                    else "$all packages; player candidates:\n" + c.joinToString("\n")
                }
            } catch (e: AdbError) {
                describe(e)
            }
            _state.update { it.copy(busy = false, sendStatus = text) }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            disconnectInternal()
            _state.update { it.copy(connected = false, status = "Disconnected") }
        }
    }

    fun resetKey() {
        viewModelScope.launch {
            disconnectInternal()
            val fp = withContext(Dispatchers.IO) {
                runCatching { container.keyStore.reset(); container.keyStore.publicKeyFingerprintMd5() }
                    .getOrElse { "error: ${it.message}" }
            }
            _state.update { it.copy(connected = false, fingerprint = fp, status = "New key generated. TVs must re-approve.") }
        }
    }

    private suspend fun disconnectInternal() {
        val s = session ?: return
        session = null
        withContext(Dispatchers.IO) { s.close() }
    }

    private fun describe(e: AdbError): String = when (e) {
        is AdbError.Unreachable -> "Can't reach the TV. Is it on and on the same Wi-Fi?"
        is AdbError.Refused -> "Connection refused. Network debugging seems off on the TV."
        is AdbError.Unauthorized -> "The TV did not trust this phone. Accept the prompt on the TV and try again."
        is AdbError.Timeout -> "Timed out. The approval prompt on the TV was not answered."
        is AdbError.Unknown -> "Failed: ${e.message}"
    }

    private companion object {
        const val TEST_VIDEO_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=43"
    }

    override fun onCleared() {
        session?.close()
        session = null
    }
}
