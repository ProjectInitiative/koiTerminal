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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.android.virtualization.koiterminal.ConfigJson
import com.android.virtualization.koiterminal.InstalledImage
import com.android.virtualization.koiterminal.new2.core.VmRegistry
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BACKUP_SUFFIX = ".bak"
private const val RESTORE_HINT = "The VM reads this file on every start; stop and start the VM to apply changes."

/**
 * Raw editor for the installed image's vm_config.json. Changes are validated
 * (JSON parse + ConfigJson model check) before being written; the previous
 * version is kept as vm_config.json.bak and can be restored.
 *
 * When [vmId] is non-null the config path is resolved via
 * [VmRegistry.installDir]; null keeps the legacy default image.
 */
@Composable
fun VmConfigEditorPage(vmId: String?, onCloseSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var configPath by remember { mutableStateOf<Path?>(null) }
    var content by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Loading…") }
    var statusIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(vmId) {
        withContext(Dispatchers.IO) {
            try {
                val path =
                    if (vmId != null) {
                        VmRegistry.installDir(context, vmId).resolve("vm_config.json")
                    } else {
                        InstalledImage.getDefault(context).configPath
                    }
                if (!Files.exists(path)) {
                    status = "No installed image found."
                    statusIsError = true
                } else {
                    configPath = path
                    content = Files.readString(path)
                    status = ""
                    statusIsError = false
                }
            } catch (e: Exception) {
                status = "Failed to load config: ${e.message}"
                statusIsError = true
            }
            loaded = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "VM config (advanced)",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = RESTORE_HINT,
            style = MaterialTheme.typography.bodySmall,
        )

        if (loaded && configPath != null) {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth().height(420.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize),
            )

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    color =
                        if (statusIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = saveConfig(context, configPath!!, content)
                            status = result.second
                            statusIsError = !result.first
                            busy = false
                        }
                    },
                ) { Text("Save") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    content = Files.readString(configPath!!)
                                    status = "Reloaded from disk."
                                    statusIsError = false
                                } catch (e: Exception) {
                                    status = "Reload failed: ${e.message}"
                                    statusIsError = true
                                }
                            }
                            busy = false
                        }
                    },
                ) { Text("Revert") }
                TextButton(
                    enabled = !busy && Files.exists(configPath!!.resolveSibling(configPath!!.fileName.toString() + BACKUP_SUFFIX)),
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = restoreBackup(context, configPath!!)
                            status = result.second
                            statusIsError = !result.first
                            if (result.first) {
                                content = Files.readString(configPath!!)
                            }
                            busy = false
                        }
                    },
                ) { Text("Restore backup") }
            }

            HorizontalDivider()
            Text(
                text = "Structural fields (disks, partitions, \$PAYLOAD_DIR paths) must match the installed image; editing those incorrectly makes the VM unbootable (restore the backup to recover).",
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (loaded) {
            Text(text = status, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Returns (success, message). Writes a .bak first, validates, restores on failure. */
private fun saveConfig(
    context: android.content.Context,
    path: Path,
    newContent: String,
): Pair<Boolean, String> =
    try {
        JsonParser.parseString(newContent) // syntax check
        val backup = path.resolveSibling(path.fileName.toString() + BACKUP_SUFFIX)
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(path, newContent)
        try {
            ConfigJson.from(context, path) // model check (throws on bad shape)
            Pair(true, "Saved. $RESTORE_HINT")
        } catch (e: Exception) {
            Files.copy(backup, path, StandardCopyOption.REPLACE_EXISTING)
            Pair(false, "Config rejected, original restored: ${rootMessage(e)}")
        }
    } catch (e: Exception) {
        Pair(false, "Save failed: ${rootMessage(e)}")
    }

private fun restoreBackup(
    context: android.content.Context,
    path: Path,
): Pair<Boolean, String> =
    try {
        val backup = path.resolveSibling(path.fileName.toString() + BACKUP_SUFFIX)
        Files.copy(backup, path, StandardCopyOption.REPLACE_EXISTING)
        ConfigJson.from(context, path)
        Pair(true, "Backup restored.")
    } catch (e: Exception) {
        Pair(false, "Restore failed: ${rootMessage(e)}")
    }

private fun rootMessage(e: Exception): String = e.cause?.message ?: e.message ?: e.toString()
