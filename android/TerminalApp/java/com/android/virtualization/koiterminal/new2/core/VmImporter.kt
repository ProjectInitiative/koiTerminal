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
package com.android.virtualization.koiterminal.new2.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.android.virtualization.koiterminal.ImageArchive
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports a VM image archive (`images.tar.gz`) into a new registry slot:
 * copies the source stream to `files/imports/<uuid>.tar.gz` (app-private, no
 * permissions needed), registers a [VmEntry], and enqueues a serialized
 * install via [Installer.enqueueVmInstall].
 *
 * See docs/MULTI_VM_PLAN.md ("Contract: import & installer").
 */
object VmImporter {
    private const val TAG = "VmImporter"
    private const val IMPORTS_DIRNAME = "imports"
    private const val ARCHIVE_SUFFIX = ".tar.gz"

    /** App-private directory holding in-flight archive imports. */
    fun importsDir(context: Context): Path =
        context.getFilesDir().toPath().resolve(IMPORTS_DIRNAME)

    /**
     * Imports the archive behind [uri] (typically from SAF
     * ACTION_OPEN_DOCUMENT) as a new VM. [name] is the optional user-visible
     * name; when null it is derived from the source file name.
     */
    @Throws(IOException::class)
    suspend fun import(context: Context, uri: Uri, name: String?): VmEntry {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            val dir = importsDir(appContext)
            Files.createDirectories(dir)
            val dst = dir.resolve("${UUID.randomUUID()}$ARCHIVE_SUFFIX")
            val sha256 =
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    copyWithSha256(input, dst)
                } ?: throw IOException("Cannot open $uri")

            val entry =
                VmEntry(
                    id = UUID.randomUUID().toString(),
                    name = name ?: deriveName(appContext, uri),
                    createdAt = System.currentTimeMillis(),
                    archiveSha256 = sha256,
                )
            VmRegistry.add(appContext, entry)
            Log.i(TAG, "Imported ${entry.name} (${entry.id}) from $uri; enqueueing install")
            Installer.enqueueVmInstall(entry.id, ImageArchive.fromPath(dst), sha256, dst)
            entry
        }
    }

    /**
     * Legacy import path for users following the README: reads
     * `/sdcard/linux/images.tar.gz` (plus an optional `cidata.iso`) into a new
     * registry slot.
     */
    @Throws(IOException::class)
    suspend fun importLegacy(context: Context, name: String?): VmEntry {
        val appContext = context.applicationContext
        val entry =
            VmEntry(
                id = UUID.randomUUID().toString(),
                name = name ?: defaultName(appContext),
                createdAt = System.currentTimeMillis(),
            )
        VmRegistry.add(appContext, entry)
        Log.i(TAG, "Legacy import ${entry.name} (${entry.id}) from /sdcard/linux; enqueueing install")
        Installer.enqueueVmInstall(entry.id, ImageArchive.fromSdCard())
        return entry
    }

    /** Copies [input] to [dst], returning the sha256 of the copied bytes. */
    private fun copyWithSha256(input: InputStream, dst: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val digestStream =
            object : InputStream() {
                override fun read(): Int {
                    val b = input.read()
                    if (b != -1) digest.update(byteArrayOf(b.toByte()))
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = input.read(b, off, len)
                    if (n != -1) digest.update(b, off, n)
                    return n
                }
            }
        Files.newOutputStream(dst).use { out -> digestStream.copyTo(out) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Best-effort display name for a content URI, e.g. "my-image" from "my-image.tar.gz". */
    private fun deriveName(context: Context, uri: Uri): String {
        val displayName =
            runCatching {
                    context.contentResolver
                        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                        }
                }
                .getOrNull()
        return displayName?.removeSuffix(ARCHIVE_SUFFIX)?.ifBlank { null } ?: defaultName(context)
    }

    private fun defaultName(context: Context): String {
        val count =
            runCatching { VmRegistry.list(context).size }.getOrDefault(0)
        return "VM ${count + 1}"
    }
}
