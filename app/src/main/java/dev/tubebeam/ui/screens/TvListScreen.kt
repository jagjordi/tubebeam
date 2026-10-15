package dev.tubebeam.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.tubebeam.AppContainer
import dev.tubebeam.data.Settings
import dev.tubebeam.data.TvDevice
import dev.tubebeam.adb.PortProbe
import dev.tubebeam.share.RowStatus
import dev.tubebeam.share.ShareViewModel
import dev.tubebeam.share.StatusDot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TvListViewModel(private val container: AppContainer) : ViewModel() {
    val tvs: StateFlow<List<TvDevice>?> = container.repo.tvs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<Settings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    /** Result of a quick TCP probe per TV, refreshed whenever the list is (re)loaded. */
    private val _status = MutableStateFlow<Map<Long, RowStatus>>(emptyMap())
    val status: StateFlow<Map<Long, RowStatus>> = _status

    init {
        viewModelScope.launch { tvs.collect { list -> list?.forEach(::probe) } }
    }

    private fun probe(tv: TvDevice) {
        viewModelScope.launch {
            val up = container.adb.probe(tv.host, tv.port, timeoutMs = ShareViewModel.PROBE_TIMEOUT_MS) == PortProbe.OPEN
            _status.update { it + (tv.id to if (up) RowStatus.ONLINE else RowStatus.OFFLINE) }
        }
    }

    fun setWakeTv(v: Boolean) = viewModelScope.launch { container.settings.setWakeTv(v) }
    fun setAlwaysUseDefault(v: Boolean) = viewModelScope.launch { container.settings.setAlwaysUseDefault(v) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvListScreen(
    vm: TvListViewModel,
    onAdd: () -> Unit,
    onOpen: (TvDevice) -> Unit,
    onHelp: () -> Unit,
    onDebug: () -> Unit,
) {
    val tvs by vm.tvs.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TubeBeam") },
                actions = {
                    IconButton(onClick = onHelp) { Icon(Icons.Outlined.HelpOutline, contentDescription = "Help") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("ADB debug") }, onClick = { menuOpen = false; onDebug() })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add TV") },
            )
        },
    ) { padding ->
        val list = tvs
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(onAdd, onHelp, Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { tv -> TvRow(tv, status[tv.id] ?: RowStatus.UNKNOWN, onClick = { onOpen(tv) }) }
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Sharing", style = MaterialTheme.typography.titleSmall)
                    SettingRow(
                        title = "Wake the TV before sending",
                        subtitle = "Sends a wake key so a sleeping TV turns on.",
                        checked = settings.wakeTv,
                        onChange = vm::setWakeTv,
                    )
                    SettingRow(
                        title = "Always use the default TV",
                        subtitle = "Skip the picker when a default is set. Off: ask every time there are several TVs.",
                        checked = settings.alwaysUseDefault,
                        onChange = vm::setAlwaysUseDefault,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Share any YouTube video from the YouTube app, Chrome or elsewhere and pick \"Send to TV\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvRow(tv: TvDevice, status: RowStatus, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Default.Tv, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(tv.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${tv.host}:${tv.port} · ${dev.tubebeam.youtube.TargetApp.displayName(tv.targetPackage)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tv.isDefault) {
                Icon(Icons.Default.Star, contentDescription = "Default", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
            }
            StatusDot(status = status)
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, onHelp: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.height(64.dp).width(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("No TVs yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add an Android TV running SmartTube. You'll approve this phone on the TV once; after that, sharing a YouTube link plays it there.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onHelp) { Text("How to prepare the TV") }
    }
}
