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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Multi-VM manager: one [VmInstance] per installed VM id, with per-VM state
 * flows and a single-VM-at-a-time start policy.
 *
 * `states` maps vmId -> [VmState] for every VM known to the manager (VMs are
 * registered lazily as they are touched). `activeVmId` is the most recently
 * started VM and is what the legacy [VmController] facade delegates to.
 *
 * See docs/MULTI_VM_PLAN.md ("Contract: VmManager").
 */
object VmManager {
    private const val TAG = "VmManager"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val instancesFlow = MutableStateFlow<Map<String, VmInstance>>(emptyMap())

    private val _activeVmId = MutableStateFlow<String?>(null)

    /** The most recently started VM, or null if none was started this process. */
    val activeVmId: StateFlow<String?> = _activeVmId.asStateFlow()

    /** Per-VM runtime states: vmId -> [VmState]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val states: StateFlow<Map<String, VmState>> =
        instancesFlow
            .flatMapLatest { instances ->
                if (instances.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    val perVmFlows: List<Flow<Pair<String, VmState>>> =
                        instances.entries.map { (id, instance) ->
                            instance.vmState.map { id to it }
                        }
                    combine(perVmFlows) { pairs: Array<Pair<String, VmState>> ->
                        pairs.toMap()
                    }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Aggregate open ports across all instances (only one VM runs at a time). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val allPorts: StateFlow<List<OpenPort>> =
        instancesFlow
            .flatMapLatest { instances ->
                if (instances.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val portFlows: List<StateFlow<List<OpenPort>>> =
                        instances.entries.map { (_, instance) -> instance.ports }
                    combine(portFlows) { lists: Array<List<OpenPort>> ->
                        lists.flatMap { it.toList() }
                    }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Must be called once from [com.android.virtualization.koiterminal.Application]. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        // Best-effort legacy install migration; advisory only.
        try {
            val migrated = VmRegistry.migrateLegacyInstall(appContext)
            if (migrated != null) {
                Log.i(TAG, "Migrated legacy install to VM '${migrated.id}'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy install migration failed", e)
        }
    }

    /** Returns (creating if needed) the [VmInstance] for the given VM id. */
    fun instance(vmId: String): VmInstance {
        instancesFlow.value[vmId]?.let { return it }
        synchronized(this) {
            instancesFlow.value[vmId]?.let { return it }
            val created = VmInstance(appContext, vmId, scope)
            instancesFlow.value = instancesFlow.value + (vmId to created)
            return created
        }
    }

    /** Per-VM state flow, created on demand. */
    fun state(vmId: String): StateFlow<VmState> = instance(vmId).vmState

    /** Per-VM open ports flow, created on demand. */
    fun ports(vmId: String): StateFlow<List<OpenPort>> = instance(vmId).ports

    /**
     * Starts the given VM. Refuses (logs, no-op) while another VM is alive —
     * single-VM-at-a-time policy; concurrent VMs are a later phase.
     */
    fun start(vmId: String) {
        val otherAlive =
            states.value.any { (id, state) -> id != vmId && state.isAlive }
        if (otherAlive) {
            Log.w(
                TAG,
                "Refusing to start VM '$vmId': another VM is already alive " +
                    "(single-VM-at-a-time policy)",
            )
            return
        }
        _activeVmId.value = vmId
        instance(vmId).start()
    }

    /** Stops the given VM. */
    fun stop(vmId: String) {
        instance(vmId).stop()
    }

    /** Resets the given VM's state back to Ready when it is safe to do so. */
    fun reset(vmId: String) {
        instance(vmId).reset()
    }

    /** Enables/disables port forwarding on the given VM's guest agent. */
    fun enablePortForwarding(vmId: String, port: Int, enable: Boolean) {
        instance(vmId).enablePortForwarding(port, enable)
    }

    /** The instance the legacy [VmController] facade delegates to. */
    fun activeInstance(): VmInstance = instance(_activeVmId.value ?: VmRegistry.LEGACY_VM_ID)

    /** True when any VM other than [vmId] is alive (Starting/Running/Stopping/Rebooting). */
    fun isAnotherVmAlive(vmId: String): Boolean =
        states.value.any { (id, state) -> id != vmId && state.isAlive }
}
