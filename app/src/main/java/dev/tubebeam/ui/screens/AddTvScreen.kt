package dev.tubebeam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.tubebeam.AppContainer
import dev.tubebeam.adb.AdbError
import dev.tubebeam.adb.PortProbe
import dev.tubebeam.data.ConnectionMode
import dev.tubebeam.data.TvDevice
import dev.tubebeam.discovery.TvIdentity
import dev.tubebeam.send.SendResult
import dev.tubebeam.youtube.TargetApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AddTvStep {
    data object Form : AddTvStep
    data object Probing : AddTvStep
    data object WaitingForApproval : AddTvStep
    data object Detecting : AddTvStep
    data class Done(val tvId: Long, val model: String?, val packageName: String?, val testStatus: String? = null, val testing: Boolean = false) : AddTvStep
    data class Failed(val message: String, val hint: String?, val suggestTls: Boolean) : AddTvStep
}

data class AddTvUiState(
    val name: String = "",
    val host: String = "",
    val port: String = "5555",
    val nameError: String? = null,
    val hostError: String? = null,
    val portError: String? = null,
    val fingerprint: String = "…",
    val step: AddTvStep = AddTvStep.Form,
)

class AddTvViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AddTvUiState())
    val state: StateFlow<AddTvUiState> = _state
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val fp = withContext(Dispatchers.IO) {
                runCatching { container.keyStore.publicKeyFingerprintMd5() }.getOrElse { "unavailable" }
            }
            _state.update { it.copy(fingerprint = fp) }
        }
    }

    fun onName(v: String) = _state.update { it.copy(name = v, nameError = null) }
    fun onHost(v: String) = _state.update { it.copy(host = v.trim(), hostError = null) }
    fun onPort(v: String) = _state.update { it.copy(port = v.filter(Char::isDigit).take(5), portError = null) }

    fun connect() {
        val s = _state.value
        val nameError = if (s.name.isBlank()) "Give the TV a name" else null
        val hostError = if (!IPV4.matches(s.host)) "Enter an IPv4 address like 192.168.1.50" else null
        val port = s.port.toIntOrNull()
        val portError = if (port == null || port !in 1..65535) "1–65535" else null
        if (nameError != null || hostError != null || portError != null) {
            _state.update { it.copy(nameError = nameError, hostError = hostError, portError = portError) }
            return
        }
        job?.cancel()
        job = viewModelScope.launch { runTrustFlow(s.name.trim(), s.host, port!!) }
    }

    private suspend fun runTrustFlow(name: String, host: String, port: Int) {
        _state.update { it.copy(step = AddTvStep.Probing) }
        when (container.adb.probe(host, port)) {
            PortProbe.REFUSED -> {
                fail(
                    "The TV at $host refused port $port.",
                    "Network (or USB) debugging seems to be off. Enable it in the TV's Developer options; some TVs turn it off after a reboot.",
                    suggestTls = true,
                )
                return
            }
            PortProbe.UNREACHABLE -> {
                fail("Can't reach $host.", "Check that the TV is on and that phone and TV are on the same Wi-Fi.", suggestTls = false)
                return
            }
            PortProbe.OPEN -> Unit
        }
        _state.update { it.copy(step = AddTvStep.WaitingForApproval) }
        try {
            container.adb.connect(host, port, connectTimeoutMs = 4_000, ioTimeoutMs = 60_000).use { session ->
                _state.update { it.copy(step = AddTvStep.Detecting) }
                val model = runCatching { session.shell("getprop ro.product.model").stdout.trim().ifEmpty { null } }.getOrNull()
                val pkg = runCatching { TargetApp.pickFrom(session.shell(TargetApp.DETECT_COMMAND).stdout) }.getOrNull()
                val serial = runCatching { TvIdentity.parse(session.shell(TvIdentity.COMMAND).stdout) }.getOrNull()
                val id = container.repo.add(
                    TvDevice(name = name, host = host, port = port, mode = ConnectionMode.LEGACY_TCP, targetPackage = pkg, serial = serial),
                )
                container.repo.markSeen(id)
                _state.update { it.copy(step = AddTvStep.Done(id, model, pkg)) }
            }
        } catch (e: AdbError) {
            when (e) {
                is AdbError.Timeout -> fail("No answer within 60 seconds.", "Look at the TV: accept the \"Allow debugging?\" prompt with \"Always allow\" ticked, then retry.", false)
                is AdbError.Unauthorized -> fail("The TV didn't trust this phone.", "Retry and accept the prompt on the TV. If no prompt appears, some TVs need one USB debugging session first; see Help.", false)
                is AdbError.Refused -> fail("Connection refused.", "Network debugging seems off on the TV.", true)
                is AdbError.Unreachable -> fail("Can't reach $host.", "Same Wi-Fi? TV on?", false)
                is AdbError.Unknown -> fail("Connection failed.", e.message, false)
            }
        }
    }

    private fun fail(message: String, hint: String?, suggestTls: Boolean) =
        _state.update { it.copy(step = AddTvStep.Failed(message, hint, suggestTls)) }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(step = AddTvStep.Form) }
    }

    fun backToForm() = _state.update { it.copy(step = AddTvStep.Form) }

    fun sendTestVideo() {
        val done = _state.value.step as? AddTvStep.Done ?: return
        viewModelScope.launch {
            _state.update { it.copy(step = done.copy(testing = true, testStatus = "Sending…")) }
            val tv = container.repo.get(done.tvId)
            val text = if (tv == null) "TV not found" else when (val r = container.sendCoordinator.send(tv, TEST_VIDEO_URL)) {
                is SendResult.Success -> "Playing on the TV via ${TargetApp.displayName(r.packageUsed)} (${r.durationMs} ms)"
                is SendResult.NoPlayerApp -> "SmartTube is not installed on this TV."
                is SendResult.Failure -> r.error.message ?: "Failed"
            }
            _state.update { it.copy(step = done.copy(testing = false, testStatus = text)) }
        }
    }

    companion object {
        private val IPV4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
        const val TEST_VIDEO_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTvScreen(vm: AddTvViewModel, onBack: () -> Unit, onDone: () -> Unit, onHelp: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add a TV") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val step = s.step) {
                AddTvStep.Form -> FormStep(s, vm, onHelp)
                AddTvStep.Probing -> WaitingStep("Checking that the TV is reachable…", s.fingerprint, showFingerprint = false, onCancel = vm::cancel)
                AddTvStep.WaitingForApproval -> WaitingStep(
                    "Look at your TV.\n\nTick \"Always allow from this computer\" and press Allow.",
                    s.fingerprint, showFingerprint = true, onCancel = vm::cancel,
                )
                AddTvStep.Detecting -> WaitingStep("Trusted. Looking for SmartTube…", s.fingerprint, showFingerprint = false, onCancel = vm::cancel)
                is AddTvStep.Done -> DoneStep(s.name, step, vm::sendTestVideo, onDone)
                is AddTvStep.Failed -> FailedStep(step, onRetry = vm::connect, onEdit = vm::backToForm, onHelp = onHelp)
            }
        }
    }
}

@Composable
private fun FormStep(s: AddTvUiState, vm: AddTvViewModel, onHelp: () -> Unit) {
    OutlinedTextField(
        value = s.name, onValueChange = vm::onName, label = { Text("Name") }, placeholder = { Text("Living Room") },
        singleLine = true, isError = s.nameError != null, supportingText = s.nameError?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = s.host, onValueChange = vm::onHost, label = { Text("IP address") }, placeholder = { Text("192.168.1.50") },
            singleLine = true, isError = s.hostError != null, supportingText = s.hostError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = s.port, onValueChange = vm::onPort, label = { Text("Port") }, singleLine = true,
            isError = s.portError != null, supportingText = s.portError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(100.dp),
        )
    }
    Text(
        "The TV needs Developer options with network (or USB) debugging on. Find its IP under Settings → Network.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onHelp) { Text("Show me how to prepare the TV") }
    Button(onClick = vm::connect, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
}

@Composable
private fun WaitingStep(message: String, fingerprint: String, showFingerprint: Boolean, onCancel: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(24.dp))
        Text(message, style = MaterialTheme.typography.titleMedium)
        if (showFingerprint) {
            Spacer(Modifier.height(16.dp))
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("The prompt should show this fingerprint:", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun DoneStep(name: String, step: AddTvStep.Done, onTest: () -> Unit, onDone: () -> Unit) {
    Text("$name is ready", style = MaterialTheme.typography.titleLarge)
    step.model?.let { Text("Device: $it", style = MaterialTheme.typography.bodyMedium) }
    if (step.packageName != null) {
        Text("Videos will open in ${TargetApp.displayName(step.packageName)}.", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(
            "SmartTube was not found on this TV. Install it, then sending will work; until then the TV has nothing to play with.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onTest, enabled = !step.testing) { Text("Send a test video") }
        Button(onClick = onDone) { Text("Done") }
    }
    step.testStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun FailedStep(step: AddTvStep.Failed, onRetry: () -> Unit, onEdit: () -> Unit, onHelp: () -> Unit) {
    Text(step.message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
    step.hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onEdit) { Text("Edit") }
        Button(onClick = onRetry) { Text("Retry") }
    }
    if (step.suggestTls) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Android 11+ TVs with \"Wireless debugging\" use a different, paired connection. Support for it is coming in a later version.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    TextButton(onClick = onHelp) { Text("TV setup help") }
}
