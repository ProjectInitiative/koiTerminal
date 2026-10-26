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
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.android.virtualization.koiterminal.ImageArchive
import com.android.virtualization.koiterminal.InstalledImage
import com.android.virtualization.koiterminal.new2.util.LoggingMutableStateFlow
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A custom [MutableStateFlow] for [InstallState] that prevents moving from a started state back to
 * a non-started state. This ensures that background status checks don't interrupt an ongoing
 * installation.
 */
private class InstallStateFlow(private val original: MutableStateFlow<InstallState>) :
    MutableStateFlow<InstallState> by original {
    override var value: InstallState
        get() = original.value
        set(newValue) {
            if (shouldIgnore(original.value, newValue)) return
            original.value = newValue
        }

    override suspend fun emit(value: InstallState) {
        if (shouldIgnore(original.value, value)) return
        original.emit(value)
    }

    override fun tryEmit(value: InstallState): Boolean {
        if (shouldIgnore(original.value, value)) return false
        return original.tryEmit(value)
    }

    /**
     * Determines if a state transition should be ignored.
     *
     * We want to prevent background status checks (which set state to Checking or NotInstalled)
     * from overriding an active installation process.
     */
    private fun shouldIgnore(current: InstallState, next: InstallState): Boolean {
        // If installation is underway, ignore background resets UNLESS the
        // installation job is no longer active (e.g., it was cancelled by the user).
        if (
            current.isStarted() &&
                (next is InstallState.Checking || next is InstallState.NotInstalled)
        ) {
            if (Installer.isInstalling) {
                Log.d("Installer", "Ignoring background update while installation is active.")
                return true
            }
        }
        return false
    }
}

object Installer {
    private const val TAG = "Installer"
    private const val ESTIMATED_DOWNLOAD_SIZE = 700L * 1024 * 1024

    private lateinit var context: Context
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var installedImage: InstalledImage

    private val _installState =
        InstallStateFlow(
            LoggingMutableStateFlow(MutableStateFlow(InstallState.Checking), "Installer")
        )
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val _showIfError = MutableStateFlow(false)
    val showIfError = _showIfError.asStateFlow()

    private val _wifiOnly = MutableStateFlow(true)
    val wifiOnly = _wifiOnly.asStateFlow()

    private var networkMonitor: NetworkMonitor? = null
    private var installJob: Job? = null

    /** Whether an installation or upgrade process is currently active. */
    val isInstalling: Boolean
        get() = installJob?.isActive == true

    fun initialize(context: Context) {
        this.context = context.applicationContext
        this.installedImage = InstalledImage.getDefault(this.context)

        networkMonitor = NetworkMonitor(context)

        repositoryScope.launch { checkInstallStatus() }
        // Seed per-VM install states from the registry (see multi-VM plan, Phase C).
        repositoryScope.launch { seedVmInstallStates() }
    }

    fun install() {
        if (installState.value.isStarted() || installState.value is InstallState.Installed) {
            return
        }
        val intent = Intent(context, VmService::class.java)
        context.startForegroundService(intent)
        installJob = repositoryScope.launch { installInternal() }
    }

    fun upgrade() {
        if (installState.value.isStarted()) {
            return
        }
        val intent = Intent(context, VmService::class.java)
        context.startForegroundService(intent)
        installJob =
            repositoryScope.launch {
                try {
                    val progressFlow = MutableStateFlow(0L)
                    _installState.value =
                        InstallState.Installing(
                            ESTIMATED_DOWNLOAD_SIZE,
                            progressFlow.asStateFlow(),
                            networkMonitor?.isWifiConnected?.value ?: false,
                        )
                    installedImage.uninstallAndBackup()
                    installInternal()
                } catch (e: IOException) {
                    _installState.value =
                        InstallState.Error(e, InstallState.ErrorCause.UninstallFailed)
                }
            }
    }

    private suspend fun installInternal() {
        val monitor =
            networkMonitor ?: throw IllegalStateException("NetworkMonitor not initialized")
        val progressFlow = MutableStateFlow(0L)
        _installState.value =
            InstallState.Installing(0L, progressFlow.asStateFlow(), monitor.isWifiConnected.value)
        try {
            downloadAndInstall(monitor, progressFlow)
            _installState.value = InstallState.Installed
        } catch (e: IOException) {
            val cause =
                if (e.message?.contains("No space left on device") == true) {
                    InstallState.ErrorCause.InstallFailedNoSpace
                } else {
                    InstallState.ErrorCause.InstallFailedUnknown
                }
            _installState.value = InstallState.Error(e, cause)
        } catch (e: CancellationException) {
            handleCancellation()
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        synchronized(this) { (this as Object).notifyAll() }
    }

    fun retryCheck() {
        val state = _installState.value
        if (state is InstallState.Error) {
            repositoryScope.launch { checkInstallStatus() }
        }
    }

    suspend fun uninstall(backupRootfs: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                _installState.value = InstallState.Checking
                if (backupRootfs) {
                    installedImage.uninstallAndBackup()
                } else {
                    installedImage.uninstallFully()
                }
                checkInstallStatus()
            } catch (e: IOException) {
                Log.e("Installer", "Failed to uninstall or backup VM", e)
                _installState.value = InstallState.Error(e, InstallState.ErrorCause.UninstallFailed)
            }
        }
    }

    fun hasBackup(): Boolean {
        return installedImage.hasBackup()
    }

    suspend fun deleteBackup(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                installedImage.deleteBackup()
                true
            } catch (e: IOException) {
                Log.e("Installer", "Failed to delete backup", e)
                _installState.value =
                    InstallState.Error(e, InstallState.ErrorCause.DeleteBackupFailed)
                false
            }
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        _wifiOnly.value = enabled
        synchronized(this) { (this as Object).notifyAll() }
    }

    // --- Per-VM (imported) installs (multi-VM plan Phase C) ---

    /** One queued per-VM install: extract [archive] into the VM's registry slot. */
    internal data class VmInstallRequest(
        val vmId: String,
        val archive: ImageArchive,
        /** Provenance sha256 of the imported archive file, when known. */
        val archiveSha256: String? = null,
        /** App-private import copy to delete after a successful install. */
        val sourceFile: Path? = null,
    )

    private val vmInstallQueue = Channel<VmInstallRequest>(Channel.UNLIMITED)
    private val vmInstallJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var vmInstallWorkerStarted = false

    /** Per-VM install states: vmId -> [InstallState]. */
    private val _vmInstallStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val vmInstallStates: StateFlow<Map<String, InstallState>> = _vmInstallStates.asStateFlow()

    private val perVmInstallStateFlows = ConcurrentHashMap<String, StateFlow<InstallState>>()

    /** Per-VM install state flow, created on demand (defaults to [InstallState.Checking]). */
    fun vmInstallState(vmId: String): StateFlow<InstallState> {
        return perVmInstallStateFlows.getOrPut(vmId) {
            _vmInstallStates
                .map { it[vmId] ?: InstallState.Checking }
                .stateIn(repositoryScope, SharingStarted.Eagerly, InstallState.Checking)
        }
    }

    /**
     * Enqueues an install of [archive] into the slot of [vmId]. Installs are
     * serialized (extraction is I/O bound); the foreground service is started
     * up-front like the legacy [install] path.
     */
    internal fun enqueueVmInstall(
        vmId: String,
        archive: ImageArchive,
        archiveSha256: String? = null,
        sourceFile: Path? = null,
    ) {
        ensureVmInstallWorker()
        val intent = Intent(context, VmService::class.java)
        context.startForegroundService(intent)
        vmInstallQueue.trySend(VmInstallRequest(vmId, archive, archiveSha256, sourceFile))
    }

    /** Cancels a queued or running per-VM install. */
    fun cancelVmInstall(vmId: String) {
        vmInstallJobs[vmId]?.cancel()
    }

    /** Clears the per-VM install state (e.g. after the VM was uninstalled). */
    fun clearVmInstallState(vmId: String) {
        _vmInstallStates.value = _vmInstallStates.value - vmId
        perVmInstallStateFlows.remove(vmId)
    }

    private fun ensureVmInstallWorker() {
        if (vmInstallWorkerStarted) return
        synchronized(this) {
            if (vmInstallWorkerStarted) return
            vmInstallWorkerStarted = true
            // Single consumer => installs are serialized.
            repositoryScope.launch {
                for (request in vmInstallQueue) {
                    val job = repositoryScope.launch { installVmArchive(request) }
                    vmInstallJobs[request.vmId] = job
                    job.join()
                    vmInstallJobs.remove(request.vmId, job)
                }
            }
        }
    }

    private suspend fun installVmArchive(request: VmInstallRequest) {
        val vmId = request.vmId
        try {
            val image = InstalledImage.forSlot(context, vmId)
            val progressFlow = MutableStateFlow(0L)
            val totalSize =
                runCatching { request.archive.getSize() }
                    .onFailure { Log.w(TAG, "Cannot determine archive size for VM $vmId", it) }
                    .getOrDefault(0L)
            _vmInstallStates.value =
                _vmInstallStates.value +
                    (vmId to
                        InstallState.Installing(
                            totalSize,
                            progressFlow.asStateFlow(),
                            networkMonitor?.isWifiConnected?.value ?: false,
                        ))
            request.archive.installTo(context, image.installDir, null).collect { bytesRead ->
                progressFlow.value = bytesRead
            }
            if (request.archiveSha256 != null) {
                updateEntryArchiveSha256(vmId, request.archiveSha256)
            }
            // The extracted image now lives in the VM slot; drop the import copy.
            request.sourceFile?.let { runCatching { Files.deleteIfExists(it) } }
            _vmInstallStates.value = _vmInstallStates.value + (vmId to InstallState.Installed)
            Log.i(TAG, "Install of VM $vmId completed")
        } catch (e: CancellationException) {
            _vmInstallStates.value = _vmInstallStates.value + (vmId to InstallState.NotInstalled(0L))
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Install of VM $vmId failed", e)
            val cause =
                if (
                    e is IOException &&
                        e.message?.contains("No space left on device") == true
                ) {
                    InstallState.ErrorCause.InstallFailedNoSpace
                } else {
                    InstallState.ErrorCause.InstallFailedUnknown
                }
            _vmInstallStates.value = _vmInstallStates.value + (vmId to InstallState.Error(e, cause))
        }
    }

    private fun updateEntryArchiveSha256(vmId: String, sha256: String) {
        try {
            VmRegistry.list(context)
                .firstOrNull { it.id == vmId }
                ?.let { VmRegistry.add(context, it.copy(archiveSha256 = sha256)) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record archive sha256 for VM $vmId", e)
        }
    }

    private suspend fun seedVmInstallStates() {
        try {
            for (entry in VmRegistry.list(context)) {
                val installed = InstalledImage.forSlot(context, entry.id).isInstalled()
                setVmInstallState(
                    entry.id,
                    if (installed) InstallState.Installed else InstallState.NotInstalled(0L),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to seed per-VM install states", e)
        }
    }

    private fun setVmInstallState(vmId: String, state: InstallState) {
        _vmInstallStates.value = _vmInstallStates.value + (vmId to state)
    }

    fun setShowIfError(enabled: Boolean) {
        _showIfError.value = enabled
    }

    private suspend fun downloadAndInstall(
        monitor: NetworkMonitor,
        progressFlow: MutableStateFlow<Long>,
    ) {
        waitForNetwork()

        while (true) {
            try {
                val archive = ImageArchive.getDefault()
                val totalSize = archive.getSize()
                _installState.value =
                    InstallState.Installing(
                        totalSize,
                        progressFlow.asStateFlow(),
                        monitor.isWifiConnected.value,
                    )
                waitForNetwork()
                archive
                    .installTo(context, installedImage.installDir) { inputStream ->
                        SuspendableInputStream(archive, inputStream)
                    }
                    .collect { bytesRead -> progressFlow.value = bytesRead }
                return
            } catch (e: IOException) {
                if (isPermanentError(e)) {
                    throw e
                }
                Log.w("Installer", "Installation failed, retrying...", e)
            }
        }
    }

    private fun isPermanentError(e: IOException): Boolean {
        if (e is java.io.FileNotFoundException) return true
        if (e is java.nio.file.AccessDeniedException) return true
        val msg = e.message
        if (msg != null && msg.contains("No space left on device")) return true
        return false
    }

    private fun handleCancellation() {
        val currentState = _installState.value
        val totalSize =
            when (currentState) {
                is InstallState.Installing -> currentState.totalBytes
                is InstallState.InstallSuspended -> currentState.totalBytes
                else -> ESTIMATED_DOWNLOAD_SIZE
            }
        _installState.value = InstallState.NotInstalled(totalSize)
    }

    private suspend fun checkInstallStatus() {
        _installState.value = InstallState.Checking
        Log.i("Installer", "installedImage.isInstalled() = ${installedImage.isInstalled()}")
        if (installedImage.isInstalled()) {
            Log.i("Installer", "installedImage.isCompatible() = ${installedImage.isCompatible(context)}")
            if (installedImage.isCompatible(context)) {
                _installState.value = InstallState.Installed
            } else {
                try {
                    val downloadSize = ImageArchive.getDefault().getSize()
                    _installState.value = InstallState.NeedsUpgrade(downloadSize)
                } catch (e: IOException) {
                    _installState.value = InstallState.NeedsUpgrade(ESTIMATED_DOWNLOAD_SIZE)
                }
            }
            return
        }

        var retryCount = 0
        while (true) {
            try {
                val downloadSize = ImageArchive.getDefault().getSize()
                _installState.value = InstallState.NotInstalled(downloadSize)
                return
            } catch (e: IOException) {
                if (retryCount >= 2) {
                    Log.w("Installer", "Failed to get image size, using estimate", e)
                    _installState.value = InstallState.NotInstalled(ESTIMATED_DOWNLOAD_SIZE)
                    return
                }
                retryCount++
                delay(1000)
            }
        }
    }

    private fun canInstall(): Boolean {
        if (ImageArchive.isLocalImage()) {
            return true
        }
        val monitor = networkMonitor ?: return false
        if (!monitor.isNetworkConnected.value) {
            Log.d("Installer", "cannot install because network disconnected")
            return false
        }
        if (wifiOnly.value && monitor.isNetworkMetered.value) {
            Log.d("Installer", "cannot install because wifi not available")
            return false
        }
        return true
    }

    /**
     * Blocks the current thread if the network is not available for installation. It updates the
     * install state to [InstallState.InstallSuspended] while waiting, and switches it back to
     * [InstallState.Installing] once the network is restored. This method relies on [Object.wait]
     * and is expected to be woken up by [NetworkMonitor] via [Object.notifyAll].
     */
    private fun waitForNetwork() {
        synchronized(Installer) {
            while (!Installer.canInstall()) {
                val currentState = Installer._installState.value
                if (currentState is InstallState.Installing) {
                    Installer._installState.value =
                        InstallState.InstallSuspended(
                            currentState.totalBytes,
                            currentState.progress,
                        )
                }
                try {
                    (Installer as Object).wait()
                } catch (e: InterruptedException) {
                    throw IOException("Installation interrupted", e)
                }
                if (Installer.installJob?.isActive != true) {
                    throw CancellationException("Installation cancelled")
                }
            }
            val currentState = Installer._installState.value
            val monitor = networkMonitor
            if (currentState is InstallState.InstallSuspended && monitor != null) {
                Installer._installState.value =
                    InstallState.Installing(
                        currentState.totalBytes,
                        currentState.progress,
                        monitor.isWifiConnected.value,
                    )
            }
        }
    }

    /**
     * An InputStream wrapper that supports pausing and resuming the download. It monitors the
     * network state via Installer.waitForNetwork() and automatically reconnects to the archive
     * stream if the connection is lost.
     */
    private class SuspendableInputStream(
        private val archive: ImageArchive,
        initialInputStream: InputStream,
    ) : InputStream() {
        private var inputStream = initialInputStream
        private var position = 0L

        @Throws(IOException::class)
        override fun read(buf: ByteArray?, offset: Int, numToRead: Int): Int {
            while (true) {
                try {
                    Installer.waitForNetwork()
                    val result = inputStream.read(buf, offset, numToRead)
                    if (result != -1) {
                        position += result
                    }
                    return result
                } catch (e: IOException) {
                    Log.d("Installer", "Read error, attempting to resume at $position", e)
                    // Wait for network recovery and reconnect
                    reconnect()
                }
            }
        }

        @Throws(IOException::class)
        override fun read(): Int {
            while (true) {
                try {
                    Installer.waitForNetwork()
                    val result = inputStream.read()
                    if (result != -1) {
                        position++
                    }
                    return result
                } catch (e: IOException) {
                    Log.d("Installer", "Read error, attempting to resume at $position", e)
                    // Wait for network recovery and reconnect
                    reconnect()
                }
            }
        }

        private fun reconnect() {
            // Wait until we can install again
            Installer.waitForNetwork()
            try {
                inputStream.close()
            } catch (ignored: IOException) {}
            try {
                inputStream = archive.getInputStream(position)
            } catch (e: IOException) {
                // If reconnect fails immediately, it will be retried in the next loop iteration
                Log.e("Installer", "Failed to reconnect")
            }
        }
    }

    /**
     * Monitors the network state and updates flows accordingly. It also notifies Installer whenever
     * the network state changes.
     */
    private class NetworkMonitor(context: Context) {
        private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

        private val _isNetworkConnected = MutableStateFlow(false)
        val isNetworkConnected = _isNetworkConnected.asStateFlow()

        private val _isNetworkMetered = MutableStateFlow(false)
        val isNetworkMetered = _isNetworkMetered.asStateFlow()

        private val _isWifiConnected = MutableStateFlow(false)
        val isWifiConnected = _isWifiConnected.asStateFlow()

        init {
            // Calculate initial state
            val network = connectivityManager?.activeNetwork
            val caps = network?.let { connectivityManager?.getNetworkCapabilities(it) }
            processCapabilities(caps)

            connectivityManager?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // No-op. processCapabilities() will be called in onCapabilitiesChanged.
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        processCapabilities(networkCapabilities)
                    }

                    override fun onLost(network: Network) {
                        // The network object passed here still represents the network just before
                        // it was lost, and it might still be considered connected if we query its
                        // capabilities. Thus, we explicitly set all network states to false.
                        updateNetworkState(isConnected = false, isWifi = false, isMetered = false)
                    }
                }
            )
        }

        private fun processCapabilities(caps: NetworkCapabilities?) {
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val isConnected =
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val isMetered =
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            updateNetworkState(isConnected, isWifi, isMetered)
        }

        private fun updateNetworkState(isConnected: Boolean, isWifi: Boolean, isMetered: Boolean) {
            _isWifiConnected.value = isWifi
            _isNetworkConnected.value = isConnected
            _isNetworkMetered.value = isMetered

            Log.d(
                "Installer",
                "update network state. wificonnected=$isWifi networkconnected=$isConnected networkmetered=$isMetered",
            )
            synchronized(Installer) { (Installer as Object).notifyAll() }
        }
    }
}
