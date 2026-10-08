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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(vm: DebugViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("TubeBeam · ADB debug") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("This phone's key fingerprint", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(s.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "The TV shows the same fingerprint in its \"Allow debugging?\" prompt.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = vm::resetKey, enabled = !s.busy) { Text("Reset key") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = s.host,
                    onValueChange = vm::onHostChange,
                    label = { Text("TV IP address") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = !s.busy,
                )
                OutlinedTextField(
                    value = s.port,
                    onValueChange = vm::onPortChange,
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                    enabled = !s.busy,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::connect, enabled = !s.busy) { Text("Connect") }
                OutlinedButton(onClick = vm::disconnect, enabled = s.connected && !s.busy) { Text("Disconnect") }
                if (s.busy) CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
            }

            Text(s.status, style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = s.command,
                onValueChange = vm::onCommandChange,
                label = { Text("Shell command") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !s.busy,
            )
            Button(onClick = vm::runShell, enabled = s.connected && !s.busy) { Text("Run shell") }

            if (s.output.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            s.output,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
            HorizontalDivider()
            Text("Phase 2: send a video", style = MaterialTheme.typography.titleSmall)
            Text(
                "Opens its own connection, wakes the TV, detects SmartTube and starts a test video.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::sendTestVideo, enabled = !s.busy) { Text("Send test video") }
                OutlinedButton(onClick = vm::listPlayerApps, enabled = !s.busy) { Text("List player apps") }
            }
            if (s.sendStatus.isNotEmpty()) Text(s.sendStatus, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
        }
    }
}
