package dev.tubebeam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preparing a TV") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("How it works")
            Body(
                "TubeBeam talks to the TV the same way Android's developer tools do, over your Wi-Fi. " +
                    "The first time, the TV asks whether to trust this phone. After that, sharing a YouTube link opens it in SmartTube, " +
                    "waking the TV and launching SmartTube if needed.",
            )

            Section("One-time setup on the TV")
            Step(1, "Settings → Device Preferences → About. Select \"Build\" seven times to enable Developer options.")
            Step(2, "Settings → Device Preferences → Developer options. Turn on \"USB debugging\" and, if shown, \"Network debugging\" (wording varies by brand).")
            Step(3, "Find the TV's IP address: Settings → Network & Internet → your Wi-Fi network.")
            Step(4, "Recommended: give the TV a fixed IP with a DHCP reservation in your router. If it does change, TubeBeam looks for the TV on the network by its device ID and updates the address by itself.")
            Step(5, "Make sure SmartTube is installed on the TV.")

            Section("Adding the TV in TubeBeam")
            Body("Tap \"Add TV\", enter a name and the IP, and tap Connect. Watch the TV: tick \"Always allow from this computer\" and press Allow. The fingerprint shown on the TV matches the one in the app.")

            Section("If something doesn't work")
            Bullet("\"Connection refused\": debugging is off on the TV. Some TVs turn it off after every reboot or update; switch it back on.")
            Bullet("\"Can't reach\": the TV is off, or phone and TV are on different networks. Guest Wi-Fi and \"client isolation\" also block it.")
            Bullet("Orange dot next to a TV: it didn't answer a quick check. Tap the search icon (or \"Find on network\" on the TV's page) to look for it under a new IP.")
            Bullet("No prompt appears on the TV: some Sony and TCL models only show it after you have connected once over USB. Alternatively revoke authorizations in Developer options and retry.")
            Bullet("\"Hasn't trusted this phone\": the TV rejected or forgot the key. Open the TV in TubeBeam and tap Reconnect.")
            Bullet("TVs with only \"Wireless debugging\" (Android 11+, random port) need pairing, which a later version will add.")

            Section("Security note")
            Body(
                "With network debugging on, anyone on your home network holding a trusted key can control the TV. Untrusted devices still get the prompt. " +
                    "Don't enable it on shared or public networks.",
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(4.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Step(n: Int, text: String) = Text("$n.  $text", style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Bullet(text: String) = Text("•  $text", style = MaterialTheme.typography.bodyMedium)
