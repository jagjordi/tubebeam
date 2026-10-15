package dev.tubebeam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.tubebeam.AppContainer
import dev.tubebeam.adb.AdbError
import dev.tubebeam.data.TvDevice
import dev.tubebeam.discovery.FindPhase
import dev.tubebeam.discovery.FindResult
import dev.tubebeam.discovery.TvIdentity
import dev.tubebeam.send.SendProgress
import dev.tubebeam.send.SendResult
import dev.tubebeam.youtube.TargetApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class TvDetailUiState(
    val tv: TvDevice? = null,
    val loaded: Boolean = false,
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val targetPackage: String = "",
    val busy: Boolean = false,
    val status: String? = null,
    val deleted: Boolean = false,
)

class TvDetailViewModel(private val container: AppContainer, private val tvId: Long) : ViewModel() {
    private val _state = MutableStateFlow(TvDetailUiState())
    val state: StateFlow<TvDetailUiState> = _state

    init {
        viewModelScope.launch {
            container.repo.observe(tvId).collect { tv ->
                _state.update { s ->
                    if (tv == null) s.copy(loaded = true, tv = null)
                    else if (!s.loaded) s.copy(
                        loaded = true, tv = tv, name = tv.name, host = tv.host, port = tv.port.toString(),
                        targetPackage = tv.targetPackage.orEmpty(),
                    )
                    else s.copy(tv = tv)
                }
            }
        }
    }

    fun onName(v: String) = _state.update { it.copy(name = v) }
    fun onHost(v: String) = _state.update { it.copy(host = v.trim()) }
    fun onPort(v: String) = _state.update { it.copy(port = v.filter(Char::isDigit).take(5)) }
    fun onPackage(v: String) = _state.update { it.copy(targetPackage = v.trim()) }

    val dirty: Boolean
        get() = _state.value.let { s ->
            val tv = s.tv ?: return false
            s.name != tv.name || s.host != tv.host || s.port != tv.port.toString() || s.targetPackage != tv.targetPackage.orEmpty()
        }

    fun save() {
        val s = _state.value
        val tv = s.tv ?: return
        val port = s.port.toIntOrNull()?.takeIf { it in 1..65535 }
        if (s.name.isBlank() || s.host.isBlank() || port == null) {
            _state.update { it.copy(status = "Name, IP and port are required.") }
            return
        }
        viewModelScope.launch {
            container.repo.update(tv.copy(name = s.name.trim(), host = s.host, port = port, targetPackage = s.targetPackage.ifBlank { null }))
            _state.update { it.copy(status = "Saved.") }
        }
    }

    fun setDefault(on: Boolean) = viewModelScope.launch { container.repo.setDefault(if (on) tvId else null) }

    fun delete() = viewModelScope.launch {
        _state.value.tv?.let { container.repo.delete(it) }
        _state.update { it.copy(deleted = true) }
    }

    fun testConnection() = runAction("Testing…") { tv ->
        container.adb.connect(tv.host, tv.port).use { s ->
            val model = s.shell("getprop ro.product.model").stdout.trim()
            if (tv.serial == null) {
                // Older records have no identity yet; learn it so the TV can be found after an IP change.
                container.repo.rememberSerial(tv.id, TvIdentity.parse(s.shell(TvIdentity.COMMAND).stdout))
            }
            "Connected. Device: $model"
        }
    }

    /** Searches the LAN for this TV by identity and saves the address it answers from. */
    fun locate() = runAction("Looking for the TV on the network…") { tv ->
        when (val r = container.finder.find(tv, onPhase = { phase ->
            val text = when (phase) {
                FindPhase.MDNS -> "Asking the network who's there…"
                FindPhase.SWEEP -> "Scanning the subnet for ADB…"
                FindPhase.VERIFY -> "Checking candidates…"
            }
            _state.update { it.copy(status = text) }
        })) {
            is FindResult.Found -> {
                container.repo.updateHost(tv.id, r.host)
                _state.update { it.copy(host = r.host) }
                "Found at ${r.host}. Address updated."
            }
            FindResult.NotFound -> "Not found. Is the TV on and on this Wi-Fi? (${TvIdentity.display(tv.serial)})"
            FindResult.NoIdentity -> "This TV's ID isn't recorded yet. Tap Test while it is reachable, then try again."
        }
    }

    fun wake() = runAction("Waking…") { tv ->
        container.adb.connect(tv.host, tv.port).use { s -> s.shell("input keyevent KEYCODE_WAKEUP"); "Wake key sent." }
    }

    fun reconnect() = runAction("Waiting for the TV to approve (up to 60 s)…") { tv ->
        container.adb.connect(tv.host, tv.port, ioTimeoutMs = 60_000).use { "Trusted again." }
    }

    fun detectPackage() = runAction("Detecting…") { tv ->
        val pkg = container.sender.detectPackage(tv.host, tv.port)
        if (pkg == null) "No SmartTube or YouTube app found on this TV." else {
            container.repo.update(tv.copy(targetPackage = pkg))
            _state.update { it.copy(targetPackage = pkg) }
            "Found ${TargetApp.displayName(pkg)}."
        }
    }

    fun sendTestVideo() = runAction("Sending…") { tv ->
        val onProgress: (SendProgress) -> Unit = { p ->
            val text = when (p) {
                SendProgress.Sending -> "Sending…"
                SendProgress.Searching -> "No answer; looking for the TV on the network…"
                is SendProgress.Relocated -> "Found at ${p.host}, sending…"
            }
            _state.update { it.copy(status = text) }
        }
        when (val r = container.sendCoordinator.send(tv, AddTvViewModel.TEST_VIDEO_URL, onProgress)) {
            is SendResult.Success -> "Playing via ${TargetApp.displayName(r.packageUsed)} (${r.durationMs} ms)."
            is SendResult.NoPlayerApp -> "SmartTube is not installed on this TV."
            is SendResult.Failure -> describe(r.error)
        }
    }

    private fun runAction(progress: String, block: suspend (TvDevice) -> String) {
        viewModelScope.launch {
            val tv = _state.value.tv ?: return@launch
            _state.update { it.copy(busy = true, status = progress) }
            val text = try {
                block(tv)
            } catch (e: AdbError) {
                describe(e)
            }
            _state.update { it.copy(busy = false, status = text) }
        }
    }

    private fun describe(e: AdbError): String = when (e) {
        is AdbError.Unreachable -> "Can't reach the TV. Is it on and on the same Wi-Fi?"
        is AdbError.Refused -> "Connection refused. Network debugging seems off on the TV."
        is AdbError.Unauthorized -> "The TV hasn't trusted this phone. Tap Reconnect and accept the prompt on the TV."
        is AdbError.Timeout -> "The TV didn't answer in time."
        is AdbError.Unknown -> "Failed: ${e.message}"
    }

    suspend fun currentTv(): TvDevice? = container.repo.observe(tvId).first()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvDetailScreen(vm: TvDetailViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    if (s.deleted || (s.loaded && s.tv == null)) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    val tv = s.tv ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tv.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Remove") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(value = s.name, onValueChange = vm::onName, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = s.host, onValueChange = vm::onHost, label = { Text("IP address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = s.port, onValueChange = vm::onPort, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(100.dp),
                )
            }
            OutlinedTextField(
                value = s.targetPackage, onValueChange = vm::onPackage, label = { Text("Player package") },
                placeholder = { Text("auto-detect at send time") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Leave empty to detect SmartTube each time.") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::detectPackage, enabled = !s.busy) { Text("Detect") }
                Button(onClick = vm::save, enabled = vm.dirty && !s.busy) { Text("Save") }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Default TV", style = MaterialTheme.typography.bodyLarge)
                    Text("Shares go here without asking.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = tv.isDefault, onCheckedChange = vm::setDefault)
            }

            HorizontalDivider()
            Text("Actions", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::testConnection, enabled = !s.busy) { Text("Test") }
                OutlinedButton(onClick = vm::wake, enabled = !s.busy) { Text("Wake") }
                OutlinedButton(onClick = vm::sendTestVideo, enabled = !s.busy) { Text("Test video") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::locate, enabled = !s.busy) { Text("Find on network") }
            }
            TextButton(onClick = vm::reconnect, enabled = !s.busy) { Text("Reconnect (re-approve on the TV)") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.busy) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                s.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            Spacer(Modifier.height(8.dp))
            val seen = tv.lastSeenOk?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "never"
            Text("Last successful send: $seen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Connection: ${tv.mode.name.replace('_', ' ').lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Device ID: ${TvIdentity.display(tv.serial) ?: "not recorded yet (tap Test)"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${tv.name}?") },
            text = { Text("The TV keeps trusting this phone until you revoke it in its Developer options.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
