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
package com.android.virtualization.koiterminal.new2.ui.main

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.InputDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.virtualization.koiterminal.new2.core.InstallState
import com.android.virtualization.koiterminal.new2.core.Installer
import com.android.virtualization.koiterminal.new2.core.TerminalAddress
import com.android.virtualization.koiterminal.new2.core.TerminalSession
import com.android.virtualization.koiterminal.new2.core.TerminalSessionType
import com.android.virtualization.koiterminal.new2.core.TerminalSessionRepository
import com.android.virtualization.koiterminal.new2.core.VmController
import com.android.virtualization.koiterminal.new2.core.VmManager
import com.android.virtualization.koiterminal.new2.core.VmRegistry
import com.android.virtualization.koiterminal.new2.core.VmSettings
import com.android.virtualization.koiterminal.new2.ui.main.SettingsViewModel
import com.android.virtualization.koiterminal.new2.core.VmState
import com.android.virtualization.koiterminal.new2.ui.MainActivity
import com.android.virtualization.koiterminal.new2.ui.PERMISSIONS
import com.android.virtualization.koiterminal.new2.ui.SettingsDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface MainUiState {
    data object Ready : MainUiState

    data object Stopped : MainUiState

    data object Booting : MainUiState

    data class Running(val outReadingPfd: ParcelFileDescriptor?, val inWritingPfd: ParcelFileDescriptor?, val terminalAddress: TerminalAddress?) : MainUiState

    data object Stopping : MainUiState

    sealed interface ErrorHandler {
        data class ShowBug(val error: Throwable) : ErrorHandler
    }

    data class Error(val handler: ErrorHandler) : MainUiState
}

sealed interface DisplayState {
    data object Hidden : DisplayState

    data object Normal : DisplayState

    data object Minimized : DisplayState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    var hasVmEverStarted = false

    val tabs: StateFlow<List<TerminalSession>> = TerminalSessionRepository.sessions
    val selectedTabId: StateFlow<String> = TerminalSessionRepository.selectedSessionId
    val selectedTab = TerminalSessionRepository.selectedSession
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            tabs.value.getOrNull(TerminalSessionRepository.selectedSessionIndex.value),
        )

    private val _displayState = MutableStateFlow<DisplayState>(DisplayState.Hidden)
    val displayState: StateFlow<DisplayState> = _displayState.asStateFlow()

    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    private val _isImeVisible = MutableStateFlow(false)
    val isImeVisible: StateFlow<Boolean> = _isImeVisible.asStateFlow()

    private val _isPanZoomMode = MutableStateFlow(false)
    val isPanZoomMode: StateFlow<Boolean> = _isPanZoomMode.asStateFlow()

    private val _hasMouse = MutableStateFlow(false)
    val hasMouse: StateFlow<Boolean> = _hasMouse.asStateFlow()

    private val _hasPhysicalKeyboard = MutableStateFlow(false)
    val hasPhysicalKeyboard: StateFlow<Boolean> = _hasPhysicalKeyboard.asStateFlow()

    private val _isMouseLocked = MutableStateFlow(false)
    val isMouseLocked: StateFlow<Boolean> =
        combine(_isMouseLocked, _hasMouse) { locked, hasMouse -> locked && hasMouse }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _useDisplayAsTouchpad = MutableStateFlow(false)
    val useDisplayAsTouchpad: StateFlow<Boolean> = _useDisplayAsTouchpad.asStateFlow()

    private val _settingsRequest = MutableStateFlow<SettingsDestination?>(null)
    val settingsRequest: StateFlow<SettingsDestination?> = _settingsRequest.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _showIfError = MutableStateFlow(false)
    val showIfError: StateFlow<Boolean> = _showIfError.asStateFlow()

    private val _hasMandatoryPermissions = MutableStateFlow(true)
    val hasMandatoryPermissions: StateFlow<Boolean> = _hasMandatoryPermissions.asStateFlow()

    private val _permissionRequired = MutableStateFlow(false)
    val permissionRequired: StateFlow<Boolean> = _permissionRequired.asStateFlow()

    fun refreshPermissionState() {
        val context = getApplication<Application>()
        // TODO(b/492409159): Remove this check if local network is no longer mandatory
        _hasMandatoryPermissions.value = true
    }

    fun handleIntent(intent: Intent) {
        if (intent.action == MainActivity.ACTION_OPEN_SETTINGS_PORT) {
            _settingsRequest.value = SettingsDestination.PortControl
            _showSettings.value = true
        } else if (intent.action == MainActivity.ACTION_OPEN_SETTINGS_KEEP_AWAKE) {
            _settingsRequest.value = SettingsDestination.Advanced
            _showSettings.value = true
        }
    }

    fun setShowSettings(show: Boolean) {
        _showSettings.value = show
        if (!show) {
            clearSettingsRequest()
        }
    }

    fun clearSettingsRequest() {
        _settingsRequest.value = null
    }

    fun setShowIfError(enabled: Boolean) {
        _showIfError.value = enabled
    }

    fun toggleDisplay() {
        _displayState.value =
            if (_displayState.value == DisplayState.Hidden) {
                DisplayState.Normal
            } else {
                DisplayState.Hidden
            }
    }

    fun setIsFullscreen(enabled: Boolean) {
        _isFullscreen.value = enabled
    }

    fun setIsImeVisible(visible: Boolean) {
        _isImeVisible.value = visible
    }

    fun setPanZoomMode(enabled: Boolean) {
        _isPanZoomMode.value = enabled
    }

    fun setMouseLocked(locked: Boolean) {
        _isMouseLocked.value = locked
    }

    fun setUseDisplayAsTouchpad(enabled: Boolean) {
        _useDisplayAsTouchpad.value = enabled
    }

    fun addTab() {
        TerminalSessionRepository.addSession()
    }

    fun addSerialTab(): Boolean {
        return TerminalSessionRepository.addSession(TerminalSessionType.SERIAL)
    }

    fun closeTab(id: String) {
        TerminalSessionRepository.removeSession(id)
    }

    fun selectTab(id: String) {
        TerminalSessionRepository.selectSession(id)
        _displayState.value = DisplayState.Hidden
    }

    /**
     * UI state of the active VM. Follows [VmManager.activeVmId] so that
     * starting/stopping any registered VM (not just the one bound at init)
     * is reflected here (multi-VM plan Phase D).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<MainUiState> =
        VmManager.activeVmId
            .flatMapLatest { activeId ->
                VmManager.state(activeId ?: VmRegistry.LEGACY_VM_ID)
            }
            .map { vmState ->
                when (vmState) {
                    is VmState.Ready -> {
                        if (hasVmEverStarted) {
                            MainUiState.Stopped
                        } else {
                            MainUiState.Ready
                        }
                    }
                    is VmState.Starting -> {
                        hasVmEverStarted = true
                        MainUiState.Booting
                    }
                    is VmState.Rebooting -> MainUiState.Booting
                    is VmState.Running -> MainUiState.Running(vmState.outReadingPfd, vmState.inWritingPfd, vmState.terminalAddress)
                    is VmState.Stopping -> MainUiState.Stopping
                    is VmState.Stopped -> {
                        if (hasVmEverStarted) {
                            MainUiState.Stopped
                        } else {
                            MainUiState.Ready
                        }
                    }
                    is VmState.Error -> {
                        setIsImeVisible(false)
                        MainUiState.Error(MainUiState.ErrorHandler.ShowBug(vmState.cause))
                    }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = MainUiState.Ready,
            )

    /** Starts the given VM, or the active/default VM when [vmId] is null. */
    fun startVm(vmId: String? = null) {
        if (!hasMandatoryPermissions.value) {
            return
        }
        viewModelScope.launch {
            if (vmId != null) VmManager.start(vmId) else VmController.start()
        }
    }

    private fun isAutoBootEnabled(): Boolean {
        val context = getApplication<Application>()
        return context
            .getSharedPreferences(SettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(SettingsViewModel.KEY_AUTO_BOOT, false)
    }

    /**
     * The VMs auto-boot should start, in registry order: registered VMs with
     * a per-VM `autoBoot` flag. When no per-VM flag is set anywhere, falls
     * back to the `auto_boot_vm_id` pref (default: first registry entry) so
     * pre-per-VM behavior is preserved.
     */
    private fun autoBootVmIds(): List<String> {
        val context = getApplication<Application>()
        val marked =
            VmRegistry.list(context).filter { VmSettings.autoBoot(context, it.id) }.map { it.id }
        if (marked.isNotEmpty()) return marked
        return listOf(SettingsViewModel.autoBootVmIdPref(context.getSharedPreferences(SettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE), context))
    }

    fun onPermissionGranted() {
        refreshPermissionState()
        _permissionRequired.value = false
    }

    fun onPermissionDenied() {
        refreshPermissionState()
        _permissionRequired.value = false
    }

    /** Stops the given VM, or the active/default VM when [vmId] is null. */
    fun stopVm(vmId: String? = null) {
        Log.i("MainViewModel", "MainViewModel.stopVm()")
        if (vmId != null) VmManager.stop(vmId) else VmController.stop()
    }

    /**
     * Called when the VM has stopped and the UI should return to the manager
     * (idle) screen instead of closing the app. Clears terminal sessions and
     * transitions back to Ready so the Start button is shown again.
     */
    fun onVmStopped() {
        Log.i("MainViewModel", "MainViewModel.onVmStopped()")
        hasVmEverStarted = false
        TerminalSessionRepository.reset()
        VmController.reset()
    }

    fun restartVm() {
        Log.i("MainViewModel", "MainViewModel.restartVm()")
        hasVmEverStarted = false
        TerminalSessionRepository.reset()
        if (VmController.vmState.value is VmState.Rebooting) {
            VmController.reset()
        } else {
            stopVm()
        }
    }

    override fun onCleared() {
        Log.i("MainViewModel", "MainViewModel.onCleared()")
        super.onCleared()
        // Intentionally do NOT stop the VM here: closing the app/activity must
        // leave the VM running in the background (VmService is a foreground
        // service and keeps the client process — and thus the crosvm client —
        // alive). The VM stops only via the explicit Stop controls, or when
        // the guest shuts itself down. Caveat: if the OS kills the whole
        // process despite the foreground service, AVF tears the VM down with
        // its client; reconnecting to a running VM after process death is
        // future work.
    }

    init {
        VmController.reset()

        val context = getApplication<Application>()
        _permissionRequired.value =
            PERMISSIONS.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        refreshPermissionState()

        val inputManager = context.getSystemService(InputManager::class.java)
        val mouseDeviceIds = mutableSetOf<Int>()
        val keyboardDeviceIds = mutableSetOf<Int>()
        val updateDeviceStatus = {
            val currentIds = inputManager.inputDeviceIds.toSet()
            // Remove devices that are no longer connected
            mouseDeviceIds.retainAll(currentIds)
            keyboardDeviceIds.retainAll(currentIds)

            // Add devices that are currently identified as a mouse or keyboard
            for (id in currentIds) {
                val device = inputManager.getInputDevice(id) ?: continue
                if (device.isVirtual) continue

                if (device.sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) {
                    mouseDeviceIds.add(id)
                }
                if (device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                    keyboardDeviceIds.add(id)
                }
            }
            _hasMouse.value = mouseDeviceIds.isNotEmpty()
            _hasPhysicalKeyboard.value = keyboardDeviceIds.isNotEmpty()
        }
        updateDeviceStatus()
        inputManager.registerInputDeviceListener(
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) {
                    updateDeviceStatus()
                }

                override fun onInputDeviceRemoved(deviceId: Int) {
                    updateDeviceStatus()
                }

                override fun onInputDeviceChanged(deviceId: Int) {
                    updateDeviceStatus()
                }
            },
            null,
        )

        // Observe installation and UI states to manage VM lifecycle.
        viewModelScope.launch {
            hasMandatoryPermissions.collectLatest { hasMandatory ->
                if (hasMandatory) {
                    launch {
                        combine(
                            Installer.installState,
                            Installer.vmInstallStates,
                        ) { legacyState, perVmStates ->
                            // Arm auto-boot when the legacy install is present or any
                            // per-VM (imported) install is complete.
                            legacyState is InstallState.Installed ||
                                perVmStates.values.any { it is InstallState.Installed }
                        }.collectLatest { installed ->
                            if (installed) {
                                // Reset sessions to start fresh upon installation completion.
                                TerminalSessionRepository.reset()

                                // Once installed, start observing UI state and trigger VM startup
                                // whenever it returns to a Ready state (only if auto-boot is
                                // enabled in Settings; otherwise the user presses Start). The VMs
                                // to boot are the per-VM autoBoot-marked entries (fallback:
                                // `auto_boot_vm_id`, default: first registry entry). With the
                                // single-VM-at-a-time policy, the first one starts; the rest
                                // are skipped (startable manually from the VM list).
                                uiState.collect { state ->
                                    if (state is MainUiState.Ready && isAutoBootEnabled()) {
                                        val candidate = autoBootVmIds().firstOrNull { vmId ->
                                            VmManager.state(vmId).value is VmState.Ready
                                        }
                                        if (candidate != null) {
                                            startVm(candidate)
                                        }
                                    }
                                }
                            } else {
                                // If installation is removed or checking, reset flags and UI.
                                // FIXME: when VM is deleted in the background, this gets stuck
                                hasVmEverStarted = false
                                _showSettings.value = false
                            }
                        }
                    }

                    launch { VmController.sessionDiscarded.collect { id -> closeTab(id) } }
                    // Reboot auto-restart and serial-tab handling for whichever VM is active.
                    @OptIn(ExperimentalCoroutinesApi::class)
                    launch {
                        VmManager.activeVmId
                            .flatMapLatest { activeId ->
                                VmManager.state(activeId ?: VmRegistry.LEGACY_VM_ID)
                            }
                            .collect { state ->
                            if (state is VmState.Rebooting) {
                                restartVm()
                                // A guest-initiated reboot should bring the VM back up
                                // regardless of the auto-boot setting.
                                startVm()
                            } else if (state is VmState.Running && state.outReadingPfd != null && state.inWritingPfd != null && state.terminalAddress == null) {
                                // When serial is ready but ttyd is not, try to add serial tab and then close current (ttyd) tab
                                val currentTabId = TerminalSessionRepository.selectedSessionId.value
                                if (addSerialTab()) {
                                    closeTab(currentTabId)
                                }
                            }
                            }
                        }
                    }
                    launch {
                        // NOTE: an empty session list no longer stops the VM —
                        // the VM keeps running (headless) when the user closes
                        // all tabs or the app; the VM list idle screen and the
                        // serial-tab auto-add allow re-attaching. Stopping is
                        // explicit (Stop button / Settings shutdown / guest
                        // poweroff → the VM-stop path resets the UI).
                        TerminalSessionRepository.sessions.collect { sessions ->
                            Log.d("MainViewModel", "sessions.size=${sessions.size}")
                        }
                    }
                }
            }
        }
    }
