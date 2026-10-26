/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.virtualization.koiterminal.new2.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.icu.number.NumberFormatter
import android.icu.number.NumberRangeFormatter
import android.icu.number.Precision
import android.icu.text.MeasureFormat
import android.icu.util.MeasureUnit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NearbyError
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.virtualization.koiterminal.GraphicsManager
import com.android.virtualization.koiterminal.R
import com.android.virtualization.koiterminal.new2.core.VmManager
import com.android.virtualization.koiterminal.new2.core.VmRegistry
import com.android.virtualization.koiterminal.new2.core.VmSettings
import com.android.virtualization.koiterminal.new2.core.VmState
import com.android.virtualization.koiterminal.new2.ui.main.NetworkConnection
import com.android.virtualization.koiterminal.new2.ui.main.SettingsViewModel
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Link shown in the network section (same as the pre-split global page). */
const val NETWORK_LIMITATION_LINK_URL =
    "https://github.com/outlawsanzhang/koiTerminal/blob/koiterminal/FAQ.md#network-settings-limitations"

/** Network connection options (moved from the global Settings screen). */
enum class NetworkConnectionOptions(val icon: ImageVector, val titleRes: Int, val descRes: Int) {
    NONE(
        Icons.Default.OfflineBolt,
        R.string.settings_network_none,
        R.string.settings_network_none_desc,
    ),
    FULL(Icons.Default.Public, R.string.settings_network_full, R.string.settings_network_full_desc),
    MANAGED_SOCKS5(
        Icons.Default.VpnKey,
        R.string.settings_network_managed_socks5,
        R.string.settings_network_managed_socks5_desc,
    ),
    DELEGATE_SOCKS5(
        Icons.Default.Directions,
        R.string.settings_network_delegate_socks5,
        R.string.settings_network_delegate_socks5_desc,
    );

    fun toSetting(): NetworkConnection {
        return NetworkConnection.valueOf(name)
    }

    companion object {
        fun fromSetting(connection: NetworkConnection): NetworkConnectionOptions {
            return NetworkConnectionOptions.valueOf(connection.name)
        }
    }
}

/**
 * Per-VM settings surface (multi-VM plan Phase 5), opened from a VM card's
 * overflow menu. Edits are persisted to `vms/<id>.prefs.json` via
 * [VmSettings] and take effect on the next VM start; fields without a
 * per-VM value keep falling back to the legacy global settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmSettingsPage(vmId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val vmName =
        remember(vmId) { VmRegistry.list(context).firstOrNull { it.id == vmId }?.name ?: vmId }
    val state by remember(vmId) { VmManager.state(vmId) }.collectAsStateWithLifecycle()

    // Per-VM values (re-read when the page is (re)created for another VM).
    var memoryMb by remember(vmId) { mutableStateOf(VmSettings.memoryMib(context, vmId)) }
    var networkConnection by
        remember(vmId) { mutableStateOf(VmSettings.networkConnectionPref(context, vmId)) }
    var socks5Delegate by
        remember(vmId) { mutableStateOf(VmSettings.socks5DelegatePref(context, vmId)) }
    var socks5LoopbackOk by
        remember(vmId) { mutableStateOf(VmSettings.socks5LoopbackOkPref(context, vmId)) }
    var graphicsAcceleration by
        remember(vmId) { mutableStateOf(VmSettings.graphicsAccelerationPref(context, vmId)) }

    var showMemoryDialog by remember { mutableStateOf(false) }
    var showGraphicsDialog by remember { mutableStateOf(false) }
    var showNetworkSelectionDialog by remember { mutableStateOf(false) }
    var showDelegatePortDialog by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(false) }
    var delegatePort by remember(vmId) { mutableStateOf(socks5Delegate.toString()) }
    var portErrorResId by remember { mutableStateOf<Int?>(null) }
    var localPermission by remember { mutableStateOf(checkLocalPermission(activity)) }
    val settingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            localPermission = checkLocalPermission(activity)
        }
    val localPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            localPermission = checkLocalPermission(activity)
            if (!localPermission) {
                settingsLauncher.launch(intentLaunchAppSettings(activity))
            }
        }

    val maxMemoryMb = remember { calculateMaxMemoryMb(context) }
    val currentNetworkOption = NetworkConnectionOptions.fromSetting(networkConnection)
    val graphicsSupported = remember { GraphicsManager.getInstance(context).isGfxstreamSupported }
    val typeToName =
        mapOf(
            GraphicsManager.AccelerationType.Lavapipe to
                stringResource(R.string.settings_graphics_renderer_software),
            GraphicsManager.AccelerationType.Gfxstream to
                stringResource(R.string.settings_graphics_renderer_gpu),
        )
    var selectedGraphicsType by remember { mutableStateOf(graphicsAcceleration) }

    val requestRestart: () -> Unit = { showRebootDialog = true }

    if (showMemoryDialog) {
        MemorySizeDialog(
            currentMemoryMb = memoryMb,
            minMemoryMb = SettingsViewModel.MIN_MEMORY_MIB,
            maxMemoryMb = maxMemoryMb,
            onDismissRequest = { showMemoryDialog = false },
            onConfirm = { mb ->
                VmSettings.setMemoryMb(context, vmId, mb)
                memoryMb = mb
                showMemoryDialog = false
                requestRestart()
            },
        )
    }

    if (showGraphicsDialog) {
        AlertDialog(
            onDismissRequest = { showGraphicsDialog = false },
            title = { Text(stringResource(R.string.settings_graphics_title)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    GraphicsManager.getInstance(context).availableAccelerationTypes.forEach {
                        type ->
                        Row(
                            Modifier.fillMaxWidth()
                                .height(56.dp)
                                .selectable(
                                    selected = (type == selectedGraphicsType),
                                    onClick = { selectedGraphicsType = type },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (type == selectedGraphicsType),
                                onClick = null,
                            )
                            Text(
                                text = typeToName[type] ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGraphicsDialog = false
                        if (graphicsAcceleration != selectedGraphicsType) {
                            VmSettings.setGraphicsAcceleration(context, vmId, selectedGraphicsType)
                            graphicsAcceleration = selectedGraphicsType
                            requestRestart()
                        }
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGraphicsDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showNetworkSelectionDialog) {
        NetworkSelectionDialog(
            currentSelection = currentNetworkOption,
            onDismissRequest = { showNetworkSelectionDialog = false },
            onConfirm = { option ->
                VmSettings.setNetworkConnection(context, vmId, option.toSetting())
                networkConnection = option.toSetting()
                showNetworkSelectionDialog = false
                requestRestart()
            },
        )
    }

    if (showDelegatePortDialog) {
        AlertDialog(
            onDismissRequest = { showDelegatePortDialog = false },
            title = { Text(stringResource(R.string.settings_delegating_port_dlg_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = delegatePort,
                        onValueChange = {
                            delegatePort = it
                            portErrorResId = null
                        },
                        label = {
                            Text(stringResource(R.string.settings_port_dlg_hint_port_number))
                        },
                        isError = portErrorResId != null,
                        supportingText = portErrorResId?.let { { Text(stringResource(it)) } },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val port = delegatePort.toIntOrNull()
                        if (port == null) {
                            portErrorResId = R.string.settings_port_dlg_error_invalid_input
                        } else if (port < 1024 || port > 65535) {
                            portErrorResId = R.string.settings_port_dlg_error_invalid_range
                        } else {
                            showDelegatePortDialog = false
                            if (socks5Delegate != port) {
                                VmSettings.setSocks5Delegate(context, vmId, port)
                                socks5Delegate = port
                                requestRestart()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_port_dlg_btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelegatePortDialog = false }) {
                    Text(stringResource(R.string.settings_port_dlg_btn_cancel))
                }
            },
        )
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = { Text(stringResource(R.string.settings_graphics_dlg_title_restart)) },
            text = { Text(stringResource(R.string.settings_graphics_dlg_message_restart)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebootDialog = false
                        if (state.isAlive) {
                            scope.launch {
                                VmManager.stop(vmId)
                                // Wait for the stop to settle before rebooting.
                                VmManager.state(vmId).first {
                                    it == VmState.Stopped || it is VmState.Error
                                }
                                VmManager.start(vmId)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_graphics_dlg_btn_restart))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) {
                    Text(stringResource(R.string.settings_graphics_dlg_btn_later))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vmName) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_btn_back_desc),
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier =
                Modifier.padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
        ) {
            SectionHeader(stringResource(R.string.settings_advanced_memory_title))
            ListItem(
                headlineContent = { Text(formatMemorySize(memoryMb)) },
                leadingContent = { Icon(Icons.Default.Memory, contentDescription = null) },
                modifier = Modifier.clickable { showMemoryDialog = true },
            )
            HorizontalDivider()

            if (graphicsSupported) {
                SectionHeader(stringResource(R.string.settings_graphics_title))
                ListItem(
                    headlineContent = {
                        Text(typeToName[graphicsAcceleration] ?: graphicsAcceleration.name)
                    },
                    leadingContent = { Icon(Icons.Default.Speed, contentDescription = null) },
                    modifier =
                        Modifier.clickable {
                            selectedGraphicsType = graphicsAcceleration
                            showGraphicsDialog = true
                        },
                )
                HorizontalDivider()
            }

            SectionHeader(stringResource(R.string.settings_network_title))
            ListItem(
                headlineContent = { Text(stringResource(currentNetworkOption.titleRes)) },
                supportingContent = { Text(stringResource(currentNetworkOption.descRes)) },
                leadingContent = {
                    Icon(imageVector = currentNetworkOption.icon, contentDescription = null)
                },
                modifier = Modifier.clickable { showNetworkSelectionDialog = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_network_open_limitations)) },
                leadingContent = {
                    Icon(imageVector = Icons.Default.WarningAmber, contentDescription = null)
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                    )
                },
                modifier =
                    Modifier.clickable {
                        val sendIntent =
                            Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, NETWORK_LIMITATION_LINK_URL)
                                type = "text/plain"
                            }
                        activity.startActivity(Intent.createChooser(sendIntent, null))
                    },
            )
            when (networkConnection) {
                NetworkConnection.MANAGED_SOCKS5 -> {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.settings_socks5_local_ok))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.settings_socks5_local_ok_desc))
                        },
                        leadingContent = {
                            Icon(imageVector = Icons.Default.NearbyError, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(checked = localPermission, onCheckedChange = null)
                        },
                        modifier =
                            Modifier.toggleable(value = localPermission, role = Role.Switch) { ok ->
                                if (ok) {
                                    // App-level permission; request it and refresh on result.
                                    localPermissionLauncher.launch(
                                        android.Manifest.permission.ACCESS_LOCAL_NETWORK
                                    )
                                } else {
                                    // Revoking needs the system app settings page.
                                    settingsLauncher.launch(intentLaunchAppSettings(activity))
                                }
                            },
                    )
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.settings_socks5_loopback_ok))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.settings_socks5_loopback_ok_desc))
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.AddToHomeScreen,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            Switch(checked = socks5LoopbackOk, onCheckedChange = null)
                        },
                        modifier =
                            Modifier.toggleable(
                                value = socks5LoopbackOk,
                                role = Role.Switch,
                            ) { ok ->
                                VmSettings.setSocks5LoopbackOk(context, vmId, ok)
                                socks5LoopbackOk = ok
                                requestRestart()
                            },
                    )
                }
                NetworkConnection.DELEGATE_SOCKS5 -> {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.settings_socks5_delegated_port))
                        },
                        supportingContent = { Text(socks5Delegate.toString()) },
                        leadingContent = { Icon(Icons.Default.Pin, contentDescription = null) },
                        modifier = Modifier.clickable { showDelegatePortDialog = true },
                    )
                }
                else -> {}
            }
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_port_title))
            PortControlContent(vmId = vmId)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

private fun calculateMaxMemoryMb(context: Context): Int {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    // Set maximum to 70% of total system RAM (mirrors SettingsViewModel).
    return (memoryInfo.totalMem / (1024 * 1024) * 0.7).toInt()
}

private fun formatMemorySize(mib: Int): String {
    if (mib >= 1000) {
        val formatter =
            NumberFormatter.withLocale(Locale.getDefault())
                .unit(MeasureUnit.GIGABYTE)
                .unitWidth(NumberFormatter.UnitWidth.SHORT)
                .precision(Precision.fixedFraction(1))
                .roundingMode(RoundingMode.DOWN)
        return formatter.format(mib.toDouble() / 1000.0).toString()
    } else {
        val formatter =
            NumberFormatter.withLocale(Locale.getDefault())
                .unit(MeasureUnit.MEGABYTE)
                .unitWidth(NumberFormatter.UnitWidth.SHORT)
        return formatter.format(mib).toString()
    }
}

@Composable
private fun NetworkSelectionDialog(
    currentSelection: NetworkConnectionOptions,
    onDismissRequest: () -> Unit,
    onConfirm: (NetworkConnectionOptions) -> Unit,
) {
    var selected by remember { mutableStateOf(currentSelection) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.settings_network_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                NetworkConnectionOptions.entries.forEachIndexed { index, option ->
                    val isSelected = option == selected
                    Row(
                        Modifier.fillMaxWidth()
                            .height(64.dp)
                            .selectable(
                                selected = isSelected,
                                onClick = { selected = option },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(
                                text = stringResource(option.titleRes),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(option.descRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selected != currentSelection) {
                        onConfirm(selected)
                    } else {
                        onDismissRequest()
                    }
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
