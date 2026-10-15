package dev.tubebeam.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.tubebeam.data.TvDevice
import dev.tubebeam.send.SendProgress

/** Card contents when more than one TV is configured. */
@Composable
fun TvPickerSheet(
    tvs: List<TvDevice>,
    defaultId: Long?,
    status: Map<Long, RowStatus>,
    onPick: (TvDevice, remember: Boolean) -> Unit,
    onLocate: (TvDevice) -> Unit,
) {
    var remember by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
        Text(
            "Send to",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        tvs.forEach { tv ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(tv, remember) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Default.Tv, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                val rowStatus = status[tv.id] ?: RowStatus.UNKNOWN
                Column(modifier = Modifier.weight(1f)) {
                    Text(tv.name, style = MaterialTheme.typography.bodyLarge)
                    val sub = when (rowStatus) {
                        RowStatus.OFFLINE -> "${tv.host} · not answering"
                        RowStatus.SEARCHING -> "Searching the network…"
                        RowStatus.NOT_FOUND -> "${tv.host} · not found on the network"
                        else -> tv.host
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tv.id == defaultId) {
                    Icon(Icons.Default.Star, contentDescription = "Default", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                }
                when (rowStatus) {
                    RowStatus.SEARCHING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    RowStatus.OFFLINE, RowStatus.NOT_FOUND -> {
                        StatusDot(status = rowStatus)
                        IconButton(onClick = { onLocate(tv) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Search, contentDescription = "Find ${tv.name} on the network")
                        }
                    }
                    else -> StatusDot(status = rowStatus)
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { remember = !remember }
                .padding(horizontal = 4.dp),
        ) {
            Checkbox(checked = remember, onCheckedChange = { remember = it })
            Text("Remember my choice", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Green: answered a probe just now. Orange: didn't. Grey: not checked yet. */
@Composable
fun StatusDot(status: RowStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        RowStatus.ONLINE -> Color(0xFF2E7D32)
        RowStatus.OFFLINE, RowStatus.NOT_FOUND -> Color(0xFFF57C00)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(modifier = modifier.size(10.dp).background(color, CircleShape))
}

@Composable
fun SendingCard(tvName: String, progress: SendProgress = SendProgress.Sending) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(20.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(16.dp))
        val text = when (progress) {
            SendProgress.Sending -> "Sending to $tvName…"
            SendProgress.Searching -> "$tvName isn't answering, looking for it on the network…"
            is SendProgress.Relocated -> "Found $tvName at ${progress.host}, sending…"
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SentCard(tvName: String, via: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(20.dp),
    ) {
        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF2E7D32))
        Spacer(Modifier.width(16.dp))
        Column {
            Text("Sent to $tvName", style = MaterialTheme.typography.bodyLarge)
            Text("Playing in $via", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ErrorCard(message: String, hint: String?, onRetry: () -> Unit, onOpenApp: () -> Unit, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onClose) { Text("Close") }
            TextButton(onClick = onOpenApp) { Text("Open TubeBeam") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
