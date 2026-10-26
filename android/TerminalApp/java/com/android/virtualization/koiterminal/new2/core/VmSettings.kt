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
package com.android.virtualization.koiterminal.new2.core

import android.content.Context
import android.util.Log
import com.android.virtualization.koiterminal.GraphicsManager
import com.android.virtualization.koiterminal.PortsStateManager
import com.android.virtualization.koiterminal.new2.ui.main.NetworkConnection
import com.android.virtualization.koiterminal.new2.ui.main.SettingsViewModel
import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-VM settings that were global before the settings split (multi-VM plan
 * Phase 5): memory, network connection, socks5, saved ports and graphics
 * acceleration. Persisted as `files/vms/<id>.prefs.json` (sibling of the
 * `vms/<id>/` install slot).
 *
 * Every field is optional; a missing field (or a missing file) falls back to
 * the legacy global `terminal_settings` preferences, so the migrated default
 * VM keeps its pre-split behavior until the user changes a setting per-VM.
 */
data class VmPrefs(
    val memoryMib: Int? = null,
    val networkConnection: String? = null,
    val socks5Delegate: Int? = null,
    val socks5LoopbackOk: Boolean? = null,
    val graphicsAcceleration: String? = null,
    val savedPorts: List<Int> = emptyList(),
    /** Whether this VM is auto-booted on app open (global auto-boot must also be on). */
    val autoBoot: Boolean? = null,
)

/**
 * Repository for the per-VM JSON prefs store, plus effective (fallback-aware)
 * accessors used by [VmInstance]/[GuestAgentController]/[DebianService] and
 * the per-VM settings UI.
 *
 * Port forwarding runtime state still flows through the (global)
 * [PortsStateManager] consumed by ForwarderHost; [seedPorts] syncs it with
 * the per-VM saved list at VM start, and port toggles are written back to
 * this store (see [GuestAgentController.enablePortForwarding]).
 */
object VmSettings {
    private const val TAG = "VmSettings"
    private const val PREFS_SUFFIX = ".prefs.json"

    private val gson = Gson()
    private val lock = Any()

    /**
     * Whether the legacy global `.PORTS` state has already been claimed by a
     * per-VM store this process. The first per-VM user (the migrated default
     * VM, via [sealLegacyPorts] or [seedPorts]) inherits it; later VMs start
     * from their own saved list.
     */
    private var legacyPortsClaimed = false

    /** Path of the per-VM prefs file: `files/vms/<id>.prefs.json`. */
    fun prefsFile(context: Context, vmId: String): Path {
        require(isValidId(vmId)) { "Invalid VM id: $vmId" }
        return VmRegistry.vmsRoot(context).resolve(vmId + PREFS_SUFFIX)
    }

    /** Reads the per-VM prefs, or null when the VM has none yet. */
    fun read(context: Context, vmId: String): VmPrefs? {
        synchronized(lock) { return readLocked(context, vmId) }
    }

    private fun readLocked(context: Context, vmId: String): VmPrefs? {
        val file = prefsFile(context, vmId)
        if (!Files.exists(file)) return null
        return try {
            gson.fromJson(Files.readString(file), VmPrefs::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Corrupt per-VM prefs for '$vmId', ignoring", e)
            null
        }
    }

    /** Applies [transform] to the VM's prefs (creating the file if needed). */
    fun write(context: Context, vmId: String, transform: (VmPrefs) -> VmPrefs): VmPrefs {
        synchronized(lock) { return writeLocked(context, vmId, transform) }
    }

    private fun writeLocked(context: Context, vmId: String, transform: (VmPrefs) -> VmPrefs): VmPrefs {
        val updated = transform(readLocked(context, vmId) ?: VmPrefs())
        val file = prefsFile(context, vmId)
        Files.createDirectories(file.parent)
        Files.writeString(file, gson.toJson(updated))
        return updated
    }

    /** Deletes the per-VM prefs file (used on uninstall). */
    fun delete(context: Context, vmId: String) {
        synchronized(lock) {
            runCatching { Files.deleteIfExists(prefsFile(context, vmId)) }
                .onFailure { Log.w(TAG, "Failed to delete prefs for '$vmId'", it) }
        }
    }

    // --- Effective (fallback-aware) accessors --------------------------------

    /** Per-VM memory, falling back to the legacy global `memory_mib`. */
    fun memoryMib(context: Context, vmId: String): Int {
        val perVm = read(context, vmId)?.memoryMib
        if (perVm != null) return perVm
        val sharedPref =
            context.getSharedPreferences(SettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPref.getInt(SettingsViewModel.KEY_MEMORY_MIB, SettingsViewModel.DEFAULT_MEMORY_MIB)
    }

    /** Per-VM network connection, falling back to the legacy global setting. */
    fun networkConnectionPref(context: Context, vmId: String): NetworkConnection {
        val perVm = read(context, vmId)?.networkConnection
        if (perVm != null) return NetworkConnection.valueOf(perVm)
        val sharedPref =
            context.getSharedPreferences(SettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        return SettingsViewModel.networkConnectionPref(sharedPref)
    }

    /** Per-VM socks5 delegate port, falling back to the legacy global setting. */
    fun socks5DelegatePref(context: Context, vmId: String): Int {
        val perVm = read(context, vmId)?.socks5Delegate
        if (perVm != null) return perVm
        val sharedPref =
            context.getSharedPreferences(SettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        return SettingsViewModel.socks5DelegatePref(sharedPref)
    }

    /** Per-VM socks5 loopback flag, falling back to the legacy global setting. */
    fun socks5LoopbackOkPref(context: Context, vmId: String): Boolean {
        val perVm = read(context, vmId)?.socks5LoopbackOk
        if (perVm != null) return perVm
        val sharedPref =
            context.getSharedPreferences(SettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        return SettingsViewModel.socks5LoopbackOkPref(sharedPref)
    }

    /** Per-VM graphics acceleration, falling back to the global [GraphicsManager]. */
    fun graphicsAccelerationPref(context: Context, vmId: String): GraphicsManager.AccelerationType {
        val perVm = read(context, vmId)?.graphicsAcceleration
        if (perVm != null) return GraphicsManager.AccelerationType.valueOf(perVm)
        return GraphicsManager.getInstance(context).accelerationType
    }

    /** Per-VM auto-boot flag; defaults to false (registry order breaks ties). */
    fun autoBoot(context: Context, vmId: String): Boolean = read(context, vmId)?.autoBoot ?: false

    fun setAutoBoot(context: Context, vmId: String, enabled: Boolean) {
        write(context, vmId) { it.copy(autoBoot = enabled) }
    }

    /** Per-VM gfxstream decision; mirrors [GraphicsManager.isGfxstreamEnabled] when falling back. */
    fun isGfxstreamEnabled(context: Context, vmId: String): Boolean {
        val perVm = read(context, vmId)?.graphicsAcceleration
        if (perVm != null) {
            return perVm == GraphicsManager.AccelerationType.Gfxstream.name
        }
        return GraphicsManager.getInstance(context).isGfxstreamEnabled()
    }

    /** Per-VM saved (persisted) port forwarding list. */
    fun savedPorts(context: Context, vmId: String): List<Int> {
        val perVm = read(context, vmId)?.savedPorts
        if (perVm != null) return perVm
        return legacyPortsSnapshot(context).sorted()
    }

    // --- Setters (create the per-VM file on first per-VM change) -------------

    fun setMemoryMb(context: Context, vmId: String, mb: Int) {
        write(context, vmId) { it.copy(memoryMib = mb) }
    }

    fun setNetworkConnection(context: Context, vmId: String, connection: NetworkConnection) {
        write(context, vmId) { it.copy(networkConnection = connection.name) }
    }

    fun setSocks5Delegate(context: Context, vmId: String, port: Int) {
        write(context, vmId) { it.copy(socks5Delegate = port) }
    }

    fun setSocks5LoopbackOk(context: Context, vmId: String, ok: Boolean) {
        write(context, vmId) { it.copy(socks5LoopbackOk = ok) }
    }

    fun setGraphicsAcceleration(context: Context, vmId: String, type: GraphicsManager.AccelerationType) {
        write(context, vmId) { it.copy(graphicsAcceleration = type.name) }
    }

    /** Adds/removes one saved port, merging with the fallback list when the file is new. */
    fun setSavedPort(context: Context, vmId: String, port: Int, enable: Boolean) {
        synchronized(lock) {
            val current = readLocked(context, vmId)
            val base =
                current?.savedPorts
                    ?: run {
                        val inherited =
                            if (legacyPortsClaimed) emptyList() else legacyPortsSnapshot(context)
                        legacyPortsClaimed = true
                        inherited.sorted()
                    }
            val updated = base.filter { it != port }.let { if (enable) it + port else it }.sorted()
            writeLocked(context, vmId) { it.copy(savedPorts = updated) }
        }
    }

    // --- Ports runtime sync ---------------------------------------------------

    /**
     * One-time claim of the legacy global `.PORTS` state for the migrated
     * default VM, so its saved ports become per-VM before anything else can
     * overwrite the global runtime state. Called from [VmManager.initialize].
     */
    fun sealLegacyPorts(context: Context, vmId: String) {
        synchronized(lock) {
            if (legacyPortsClaimed) return
            legacyPortsClaimed = true
            if (readLocked(context, vmId) != null) return
            val snapshot = legacyPortsSnapshot(context)
            if (snapshot.isEmpty()) return
            writeLocked(context, vmId) { it.copy(savedPorts = snapshot.sorted()) }
            Log.d(TAG, "Sealed ${snapshot.size} legacy saved port(s) into prefs of '$vmId'")
        }
    }

    /**
     * Syncs the global port-forwarding runtime state ([PortsStateManager],
     * consumed by ForwarderHost/guest setup) with this VM's saved list.
     * Called from [VmInstance.start] before the guest agent is brought up.
     *
     * A VM without per-VM prefs yet inherits the current global saved ports
     * (unless those were already claimed by another VM) and owns them from
     * then on.
     */
    fun seedPorts(context: Context, vmId: String) {
        synchronized(lock) {
            val global = PortsStateManager.getInstance(context)
            val prefs = readLocked(context, vmId)
            val saved =
                if (prefs != null && !(prefs.savedPorts.isEmpty() && !legacyPortsClaimed)) {
                    legacyPortsClaimed = true
                    prefs.savedPorts
                } else if (prefs != null) {
                    // Prefs file exists but ports were never set per-VM (e.g. the
                    // file was created by a memory edit): inherit the legacy
                    // global saved ports once, like a missing file would.
                    legacyPortsClaimed = true
                    val inherited = legacyPortsSnapshot(context).sorted()
                    if (inherited.isNotEmpty()) {
                        writeLocked(context, vmId) { it.copy(savedPorts = inherited) }
                    }
                    inherited
                } else {
                    val inherited =
                        if (legacyPortsClaimed) emptyList()
                        else legacyPortsSnapshot(context).sorted()
                    legacyPortsClaimed = true
                    writeLocked(context, vmId) { it.copy(savedPorts = inherited) }
                    inherited
                }
            global.clearEnabledPorts()
            saved.forEach { global.updateEnabledPort(it, true) }
            Log.d(TAG, "Seeded ${saved.size} saved port(s) for VM '$vmId'")
        }
    }

    private fun legacyPortsSnapshot(context: Context): Set<Int> =
        PortsStateManager.getInstance(context).getEnabledPorts()

    /** Ids become file names; only allow safe slugs (mirrors VmRegistry). */
    private fun isValidId(id: String): Boolean {
        return id.isNotEmpty() &&
            id.length <= 128 &&
            id.none { it == '/' || it == '\\' } &&
            id != "." &&
            id != ".." &&
            id.none { it.isISOControl() }
    }
}
