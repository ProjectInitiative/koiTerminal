/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.virtualization.koiterminal.new2.core.InstallState
import com.android.virtualization.koiterminal.new2.core.Installer
import com.android.virtualization.koiterminal.new2.core.TerminalSessionRepository
import com.android.virtualization.koiterminal.new2.core.VmEntry
import com.android.virtualization.koiterminal.new2.core.VmManager
import com.android.virtualization.koiterminal.new2.core.VmRegistry
import com.android.virtualization.koiterminal.new2.core.VmSettings
import com.android.virtualization.koiterminal.new2.core.VmState

/**
 * Idle (Ready) screen content: one card per registered VM (name, state chip,
 * Start/Stop, overflow: Edit config / Rename / Uninstall), plus the
 * "+ Add VM" entry point into the import dialog (multi-VM plan Phase 4).
 *
 * [onChanged] is invoked after rename/uninstall so the caller can re-read the
 * registry.
 */
@Composable
fun VmListContent(
    entries: List<VmEntry>,
    vmInstallStates: Map<String, InstallState>,
    onStartVm: (String) -> Unit,
    onStopVm: (String) -> Unit,
    onEditConfig: (String) -> Unit,
    onSettings: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var renameTarget by remember { mutableStateOf<VmEntry?>(null) }
    var uninstallTarget by remember { mutableStateOf<VmEntry?>(null) }

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (entries.isEmpty()) {
            Text(
                text = "No VMs installed yet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        for (entry in entries) {
            VmCard(
                entry = entry,
                installState = vmInstallStates[entry.id],
                onStartVm = onStartVm,
                onStopVm = onStopVm,
                onEditConfig = onEditConfig,
                onSettings = onSettings,
                onRename = { renameTarget = entry },
                onUninstall = { uninstallTarget = entry },
            )
        }
    }

    renameTarget?.let { target ->
        RenameVmDialog(
            entry = target,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                try {
                    VmRegistry.rename(context, target.id, newName)
                } catch (e: Exception) {
                    Toast.makeText(context, "Rename failed: ${e.message}", Toast.LENGTH_SHORT)
                        .show()
                }
                renameTarget = null
                onChanged()
            },
        )
    }

    uninstallTarget?.let { target ->
        UninstallVmDialog(
            entry = target,
            onDismiss = { uninstallTarget = null },
            onConfirm = {
                try {
                    // Stop first so the slot is not in use, then drop sessions
                    // bound to it and clear its install state.
                    VmManager.stop(target.id)
                    TerminalSessionRepository.removeSessionsForVm(target.id)
                    Installer.cancelVmInstall(target.id)
                    VmRegistry.remove(context, target.id)
                    VmSettings.delete(context, target.id)
                    Installer.clearVmInstallState(target.id)
                } catch (e: Exception) {
                    Toast.makeText(context, "Uninstall failed: ${e.message}", Toast.LENGTH_SHORT)
                        .show()
                }
                uninstallTarget = null
                onChanged()
            },
        )
    }
}

/**
 * One VM card: name, state chip from [VmManager.state], Start/Stop buttons
 * and an overflow menu (Edit config / Rename / Uninstall).
 */
@Composable
private fun VmCard(
    entry: VmEntry,
    installState: InstallState?,
    onStartVm: (String) -> Unit,
    onStopVm: (String) -> Unit,
    onEditConfig: (String) -> Unit,
    onSettings: (String) -> Unit,
    onRename: () -> Unit,
    onUninstall: () -> Unit,
) {
    // Per-VM UI collects the state/ports flows of VmManager directly, not the
    // legacy VmController facade (multi-VM plan Phase 4).
    val state by remember(entry.id) { VmManager.state(entry.id) }.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val installing =
        installState is InstallState.Installing || installState is InstallState.InstallSuspended
    val anyAlive = VmManager.isAnyVmAlive()
    val startEnabled =
        !anyAlive && !installing && (installState == null || installState is InstallState.Installed)

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                VmStateChip(state)
            }
            if (state.isAlive) {
                Button(onClick = { onStopVm(entry.id) }) {
                    Text("Stop")
                }
            } else {
                Button(enabled = startEnabled, onClick = { onStartVm(entry.id) }) {
                    Text("Start")
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "VM options",
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit config") },
                        onClick = {
                            menuOpen = false
                            onEditConfig(entry.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = {
                            menuOpen = false
                            onSettings(entry.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Uninstall") },
                        onClick = {
                            menuOpen = false
                            onUninstall()
                        },
                    )
                }
            }
        }
    }
}

/** Stopped/Booting/Running/… chip for the VM's current state. */
@Composable
private fun VmStateChip(state: VmState) {
    val (label, container, content) =
        when (state) {
            is VmState.Error ->
                Triple(
                    "Error",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
            is VmState.Starting ->
                Triple(
                    "Booting",
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
            is VmState.Running ->
                Triple(
                    "Running",
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
            is VmState.Rebooting ->
                Triple(
                    "Rebooting",
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
            is VmState.Stopping ->
                Triple(
                    "Stopping",
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
            else ->
                Triple(
                    "Stopped",
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun RenameVmDialog(
    entry: VmEntry,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(entry.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename VM") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun UninstallVmDialog(
    entry: VmEntry,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uninstall \"${entry.name}\"?") },
        text = {
            Text("This deletes the VM's image files and registry entry. Other VMs are not affected.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Uninstall") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
