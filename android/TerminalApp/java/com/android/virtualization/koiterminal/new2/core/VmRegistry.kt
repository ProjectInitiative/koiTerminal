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
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** One installed VM in the app-private registry. */
data class VmEntry(
    /** Stable slug used for paths (`vms/<id>/`) and prefs keys. */
    val id: String,
    /** User-visible name (launcher list). */
    val name: String,
    val createdAt: Long,
    /** Provenance of the imported images.tar.gz, when known. */
    val archiveSha256: String? = null,
)

/**
 * Registry of installed VMs, persisted as `files/vm_registry.json`, with one
 * install slot per VM under `files/vms/<id>/`.
 *
 * See docs/MULTI_VM_PLAN.md ("Contract: VmEntry + registry").
 */
object VmRegistry {
    private const val TAG = "VmRegistry"
    private const val REGISTRY_FILENAME = "vm_registry.json"
    private const val VMS_DIRNAME = "vms"

    /** Directory name of the legacy (pre multi-VM) install. */
    const val LEGACY_INSTALL_DIRNAME = "linux"

    /** Id given to the migrated legacy install. */
    const val LEGACY_VM_ID = "default"

    /** Name given to the migrated legacy install when no build target is known. */
    const val LEGACY_VM_NAME = "koiTerminal"

    private val gson = Gson()
    private val lock = Any()

    /** Path of the registry file. */
    fun registryFile(context: Context): Path =
        context.getFilesDir().toPath().resolve(REGISTRY_FILENAME)

    /** Root directory containing all per-VM install slots. */
    fun vmsRoot(context: Context): Path = context.getFilesDir().toPath().resolve(VMS_DIRNAME)

    /** Install slot directory for the given VM id (`files/vms/<id>`). */
    fun installDir(context: Context, id: String): Path {
        require(isValidId(id)) { "Invalid VM id: $id" }
        return vmsRoot(context).resolve(id)
    }

    /** Reads all registered VM entries. Missing or corrupt registry => empty list. */
    fun list(context: Context): List<VmEntry> {
        synchronized(lock) {
            return readRegistryLocked(context)
        }
    }

    /** Registers a VM entry and creates its install slot directory. */
    @Throws(IOException::class)
    fun add(context: Context, entry: VmEntry) {
        require(isValidId(entry.id)) { "Invalid VM id: ${entry.id}" }
        synchronized(lock) {
            val entries = readRegistryLocked(context).filterNot { it.id == entry.id }
            writeRegistryLocked(context, entries + entry)
            Files.createDirectories(installDir(context, entry.id))
        }
    }

    /** Deletes the install slot and registry entry of the given VM id. */
    @Throws(IOException::class)
    fun remove(context: Context, id: String) {
        synchronized(lock) {
            val slot = installDir(context, id)
            if (Files.exists(slot)) {
                Files.walk(slot).use { walk ->
                    walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
            val entries = readRegistryLocked(context).filterNot { it.id == id }
            writeRegistryLocked(context, entries)
        }
    }

    /** Renames a VM (registry metadata only; slot path is keyed by id). */
    @Throws(IOException::class)
    fun rename(context: Context, id: String, name: String) {
        synchronized(lock) {
            val entries = readRegistryLocked(context)
            val updated =
                entries.map { if (it.id == id) it.copy(name = name) else it }
            writeRegistryLocked(context, updated)
        }
    }

    /**
     * Migrates a legacy single-VM install (`files/linux/`) into the first
     * registry entry. No-op when there is no legacy install or when the
     * registry already has entries. Returns the created entry, or null.
     *
     * Per the multi-VM plan the legacy directory is kept until the migrated
     * VM has successfully booted once (the old dir is only deleted later by
     * the runtime, once it no longer reads it).
     */
    fun migrateLegacyInstall(context: Context): VmEntry? {
        val legacyDir = context.getFilesDir().toPath().resolve(LEGACY_INSTALL_DIRNAME)
        val legacyConfig = legacyDir.resolve("vm_config.json")
        synchronized(lock) {
            if (!Files.exists(legacyConfig)) return null
            if (readRegistryLocked(context).isNotEmpty()) return null

            val target =
                runCatching {
                        com.android.virtualization.koiterminal.InstalledImage.getDefault(context)
                            .buildInfo
                            ?.target
                    }
                    .getOrNull()
            val entry =
                VmEntry(
                    id = LEGACY_VM_ID,
                    name = target ?: LEGACY_VM_NAME,
                    createdAt = System.currentTimeMillis(),
                )
            val slot = installDir(context, entry.id)
            // Atomic rename of the legacy dir into the slot: instant, and
            // preserves the sparse root_part (a dense copy would materialize
            // 400+ GiB of zeros and eventually hit ENOSPC; hard links are
            // blocked by the app-data security policy on modern Android).
            Files.move(legacyDir, slot)
            writeRegistryLocked(context, listOf(entry))
            Log.d(TAG, "Migrated legacy install to slot ${entry.id} ($slot)")
            return entry
        }
    }

    private fun readRegistryLocked(context: Context): List<VmEntry> {
        val file = registryFile(context)
        if (!Files.exists(file)) return emptyList()
        return try {
            val type = object : TypeToken<List<VmEntry>>() {}.type
            gson.fromJson<List<VmEntry>>(Files.readString(file), type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Corrupt VM registry, starting empty", e)
            emptyList()
        }
    }

    private fun writeRegistryLocked(context: Context, entries: List<VmEntry>) {
        val file = registryFile(context)
        Files.createDirectories(file.parent)
        Files.writeString(file, gson.toJson(entries))
    }

    /** Ids become directory names; only allow safe slugs. */
    private fun isValidId(id: String): Boolean {
        return id.isNotEmpty() &&
            id.length <= 128 &&
            id.none { it == '/' || it == '\\' } &&
            id != "." &&
            id != ".." &&
            id.none { it.isISOControl() }
    }
}
