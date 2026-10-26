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

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.android.virtualization.koiterminal.new2.core.VmImporter
import kotlinx.coroutines.launch

/**
 * Import dialog for adding a new VM: SAF ACTION_OPEN_DOCUMENT picker for an
 * `images.tar.gz` archive, optional VM name, and a legacy
 * `/sdcard/linux/images.tar.gz` import option for users following the README.
 *
 * On confirm, the stream is copied to `files/imports/<uuid>.tar.gz`, a
 * [com.android.virtualization.koiterminal.new2.core.VmEntry] is created, and
 * the install is enqueued ([VmImporter]).
 */
@Composable
fun ImportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDisplayName by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                selectedUri = uri
                selectedDisplayName = queryDisplayName(context, uri)
            }
        }

    fun confirmImport() {
        val uri = selectedUri ?: return
        importing = true
        error = null
        scope.launch {
            try {
                VmImporter.import(context, uri, name.ifBlank { null })
                importing = false
                onDismiss()
            } catch (e: Exception) {
                importing = false
                error = e.message ?: "Import failed"
            }
        }
    }

    fun confirmLegacyImport() {
        importing = true
        error = null
        scope.launch {
            try {
                VmImporter.importLegacy(context, name.ifBlank { null })
                importing = false
                onDismiss()
            } catch (e: Exception) {
                importing = false
                error = e.message ?: "Import failed"
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text("Add VM") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        // Specific mime types with a */* fallback; some file
                        // providers report neither gzip nor tar.
                        picker.launch(
                            arrayOf("application/gzip", "application/x-tar", "*/*")
                        )
                    },
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        selectedDisplayName?.let { "Change file… ($it)" }
                            ?: "Choose images.tar.gz…"
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { confirmLegacyImport() },
                    enabled = !importing,
                ) {
                    Text("Use /sdcard/linux/images.tar.gz (legacy)")
                }
                if (importing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Importing…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { confirmImport() },
                enabled = selectedUri != null && !importing,
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) { Text("Cancel") }
        },
    )
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
        }
        .getOrNull()
}
