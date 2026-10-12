package app.truenasdock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenasdock.api.model.ServiceVerb

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel, onLoggedOut: () -> Unit) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.systemInfo?.hostname?.ifBlank { "TrueNAS" } ?: "TrueNAS") },
                actions = {
                    TextButton(onClick = viewModel::refresh) { Text("Refresh") }
                    TextButton(
                        onClick = {
                            viewModel.logout()
                            onLoggedOut()
                        },
                    ) { Text("Log out") }
                },
            )
        },
    ) { padding ->
        if (state.isLoading && state.systemInfo == null) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.error?.let {
                    item { Text(it, color = MaterialTheme.colorScheme.error) }
                }

                state.systemInfo?.let { info ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), Arrangement.spacedBy(4.dp)) {
                                Text("System", style = MaterialTheme.typography.titleMedium)
                                Text("Version: ${info.version}")
                                Text("Model: ${info.model.ifBlank { "—" }}")
                                Text("Uptime: ${info.uptime.ifBlank { "—" }}")
                                state.realtime?.let { live ->
                                    Text("CPU: ${live.cpuUsage?.let { "%.1f%%".format(it) } ?: "—"}")
                                    live.memory?.let { mem ->
                                        val used = mem.total - mem.available
                                        Text("Memory used: $used / ${mem.total}")
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.alerts.isNotEmpty()) {
                    item { Text("Alerts (${state.alerts.size})", style = MaterialTheme.typography.titleMedium) }
                    items(state.alerts, key = { it.uuid }) { alert ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), Arrangement.spacedBy(4.dp)) {
                                Text("${alert.level}: ${alert.formatted ?: alert.text ?: alert.klass}")
                                TextButton(onClick = { viewModel.dismissAlert(alert.uuid) }) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }

                if (state.pools.isNotEmpty()) {
                    item { Text("Storage pools", style = MaterialTheme.typography.titleMedium) }
                    items(state.pools, key = { it.id }) { pool ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), Arrangement.spacedBy(2.dp)) {
                                Text(pool.name, style = MaterialTheme.typography.titleSmall)
                                Text("Status: ${pool.status.ifBlank { "—" }} · Healthy: ${pool.healthy}")
                                pool.statusDetail?.let { Text(it) }
                            }
                        }
                    }
                }

                if (state.services.isNotEmpty()) {
                    item { Text("Services", style = MaterialTheme.typography.titleMedium) }
                    items(state.services, key = { it.id }) { service ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("${service.service} (${service.state})")
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (service.isRunning) {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.controlService(ServiceVerb.STOP, service.service)
                                            },
                                        ) { Text("Stop") }
                                        Button(
                                            onClick = {
                                                viewModel.controlService(ServiceVerb.RESTART, service.service)
                                            },
                                        ) { Text("Restart") }
                                    } else {
                                        Button(
                                            onClick = {
                                                viewModel.controlService(ServiceVerb.START, service.service)
                                            },
                                        ) { Text("Start") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
