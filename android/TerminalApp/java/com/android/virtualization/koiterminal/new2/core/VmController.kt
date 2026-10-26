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
import android.system.virtualmachine.VirtualMachine
import android.util.Log
import com.android.virtualization.koiterminal.CertificateUtils
import com.android.virtualization.koiterminal.GraphicsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Legacy single-VM facade over [VmManager].
 *
 * Delegates to the active VM instance (the most recently started VM, or the
 * migrated default VM) so existing call sites keep compiling during the
 * multi-VM migration. New code should use [VmManager] directly with an
 * explicit vmId.
 */
object VmController {
    private val TAG = "VmController"

    private lateinit var context: Context
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** State of the active VM. */
    val vmState: StateFlow<VmState>
        get() = VmManager.activeInstance().vmState

    private val _sessionDiscarded = MutableSharedFlow<String>()
    val sessionDiscarded: SharedFlow<String> = _sessionDiscarded.asSharedFlow()

    /** Open ports across all VMs (only one runs at a time). */
    val ports: StateFlow<List<OpenPort>> =
        VmManager.allPorts.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    /** The AVF VirtualMachine of the active VM. */
    val virtualMachine: VirtualMachine?
        get() = VmManager.activeInstance().virtualMachine

    fun initialize(context: Context) {
        this.context = context.applicationContext
        VmManager.initialize(this.context)
        val key = CertificateUtils.createOrGetKey()
        CertificateUtils.writeCertificateToFile(this.context, key.certificate)
    }

    fun reset() {
        VmManager.reset(activeVmId())
    }

    fun enablePortForwarding(port: Int, enable: Boolean) {
        VmManager.enablePortForwarding(activeVmId(), port, enable)
    }

    val graphicsAccelerationType: GraphicsManager.AccelerationType
        get() = GraphicsManager.getInstance(context).accelerationType

    val isGraphicsAccelerationSupported: Boolean
        get() = GraphicsManager.getInstance(context).isGfxstreamSupported

    fun setGraphicsAccelerationType(type: GraphicsManager.AccelerationType) {
        GraphicsManager.getInstance(context).accelerationType = type
    }

    fun shutdownVm() {
        VmManager.activeInstance().shutdownVm()
    }

    fun pullClipboardFromGuest() {
        VmManager.activeInstance().pullClipboardFromGuest()
    }

    fun pushClipboardToGuest() {
        VmManager.activeInstance().pushClipboardToGuest()
    }

    fun requestSessionDiscard(sessionId: String) {
        repositoryScope.launch { _sessionDiscarded.emit(sessionId) }
    }

    fun resizeTtyWindow(columns: Int, rows: Int) {
        VmManager.activeInstance().resizeTtyWindow(columns, rows)
    }

    // It should add and then remove a display to reflect the change.
    fun resizeDisplay(width: Int, height: Int, dpi: Int, refreshRate: Int) {
        VmManager.activeInstance().resizeDisplay(width, height, dpi, refreshRate)
    }

    fun start() {
        VmManager.start(activeVmId())
    }

    fun stop() {
        VmManager.stop(activeVmId())
    }

    private fun activeVmId(): String =
        VmManager.activeVmId.value
            ?: VmRegistry.list(context).firstOrNull()?.id
            ?: VmRegistry.LEGACY_VM_ID
}
