package com.example.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.ServerInfo
import com.example.model.ServerState
import com.example.model.SharedFile
import com.example.model.SharedText
import com.example.model.TransferProgress
import com.example.network.NetworkMonitor
import com.example.network.PortSelector
import com.example.server.LocalShareHttpServer
import com.example.service.LocalShareService
import com.example.storage.LocalShareDatabase
import com.example.storage.StorageManager
import com.example.utils.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class LocalShareViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val database = LocalShareDatabase.getDatabase(context)
    val fileDao = database.fileDao()
    val textDao = database.textDao()
    val storageManager = StorageManager(context)
    val networkMonitor = NetworkMonitor(context)

    private var httpServer: LocalShareHttpServer? = null

    private val _serverInfo = MutableStateFlow(ServerInfo(state = ServerState.STOPPED))
    val serverInfo: StateFlow<ServerInfo> = _serverInfo.asStateFlow()

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    private val _activeTransfer = MutableStateFlow<TransferProgress?>(null)
    val activeTransfer: StateFlow<TransferProgress?> = _activeTransfer.asStateFlow()

    val sharedFiles: StateFlow<List<SharedFile>> = fileDao.getAllFiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sharedTexts: StateFlow<List<SharedText>> = textDao.getAllTexts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalFilesSize: StateFlow<Long> = fileDao.getTotalSize()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val fileCount: StateFlow<Int> = fileDao.getFileCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val isWifiConnected = MutableStateFlow(false)

    init {
        // Observe network state changes
        viewModelScope.launch {
            networkMonitor.networkState.collect { netState ->
                isWifiConnected.value = netState.isConnected
                if (!netState.isConnected) {
                    if (_serverInfo.value.state == ServerState.ACTIVE) {
                        stopServerInternal(isWifiLost = true)
                    } else {
                        _serverInfo.value = ServerInfo(state = ServerState.NO_WIFI)
                    }
                } else {
                    // Wi-Fi connected or IP updated
                    val currentIp = netState.ipv4Address
                    if (_serverInfo.value.state == ServerState.NO_WIFI || _serverInfo.value.state == ServerState.STOPPED) {
                        // Auto-start server on launch if Wi-Fi is available
                        startServer(preferredIp = currentIp)
                    } else if (_serverInfo.value.state == ServerState.ACTIVE && _serverInfo.value.ipAddress != currentIp) {
                        // Rebind server to new IP
                        startServer(preferredIp = currentIp)
                    }
                }
            }
        }
    }

    fun toggleSharing() {
        if (_serverInfo.value.state == ServerState.ACTIVE) {
            stopServer()
        } else {
            startServer()
        }
    }

    fun startServer(preferredIp: String? = null) {
        viewModelScope.launch {
            _serverInfo.value = ServerInfo(state = ServerState.STARTING)

            val ip = preferredIp ?: networkMonitor.getActiveWifiIpAddress()
            if (ip.isNullOrBlank()) {
                _serverInfo.value = ServerInfo(
                    state = ServerState.NO_WIFI,
                    errorMessage = "Connect to Wi-Fi to start sharing."
                )
                _qrBitmap.value = null
                return@launch
            }

            try {
                // Stop any previous server instance
                stopServerInternal(isWifiLost = false)

                val port = withContext(Dispatchers.IO) {
                    PortSelector.findAvailablePort(8080)
                }

                val server = LocalShareHttpServer(
                    context = context,
                    fileDao = fileDao,
                    textDao = textDao,
                    storageManager = storageManager,
                    port = port
                )

                withContext(Dispatchers.IO) {
                    server.start()
                }

                httpServer = server

                // Observe active transfer from server
                launch {
                    server.activeTransfer.collect { transfer ->
                        _activeTransfer.value = transfer
                    }
                }

                val fullUrl = "http://$ip:$port"
                _serverInfo.value = ServerInfo(
                    state = ServerState.ACTIVE,
                    ipAddress = ip,
                    port = port
                )

                // Generate QR Code
                withContext(Dispatchers.Default) {
                    val qr = QrCodeGenerator.generateQrBitmap(fullUrl)
                    _qrBitmap.value = qr
                }

                // Start Foreground Service
                LocalShareService.startService(context, fullUrl)

            } catch (e: Exception) {
                e.printStackTrace()
                _serverInfo.value = ServerInfo(
                    state = ServerState.ERROR,
                    errorMessage = e.localizedMessage ?: "Failed to start local server"
                )
                _qrBitmap.value = null
            }
        }
    }

    fun stopServer() {
        viewModelScope.launch {
            _serverInfo.value = ServerInfo(state = ServerState.STOPPING)
            stopServerInternal(isWifiLost = false)
            _serverInfo.value = ServerInfo(state = ServerState.STOPPED)
            _qrBitmap.value = null
        }
    }

    private suspend fun stopServerInternal(isWifiLost: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                httpServer?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                httpServer = null
            }
        }
        LocalShareService.stopService(context)
        if (isWifiLost) {
            _serverInfo.value = ServerInfo(
                state = ServerState.NO_WIFI,
                errorMessage = "Connect to Wi-Fi to start sharing."
            )
            _qrBitmap.value = null
        }
    }

    fun addFilesFromUris(uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                val file = storageManager.importFromUri(uri)
                if (file != null) {
                    fileDao.insertFile(file)
                }
            }
        }
    }

    fun deleteFile(file: SharedFile) {
        viewModelScope.launch {
            storageManager.deleteFile(file)
            fileDao.deleteFileById(file.id)
        }
    }

    fun deleteText(textId: String) {
        viewModelScope.launch {
            textDao.deleteTextById(textId)
        }
    }

    fun exportTextAsFile(content: String, onDone: ((SharedFile) -> Unit)? = null) {
        viewModelScope.launch {
            val sharedFile = storageManager.exportTextToFile(content)
            fileDao.insertFile(sharedFile)
            onDone?.invoke(sharedFile)
        }
    }

    fun openFile(sharedFile: SharedFile, onFailed: () -> Unit = {}) {
        try {
            val uri = storageManager.getContentUri(sharedFile)
            if (uri != null) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, sharedFile.mimeType.ifBlank { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                onFailed()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onFailed()
        }
    }

    fun shareFile(sharedFile: SharedFile, onFailed: () -> Unit = {}) {
        try {
            val uri = storageManager.getContentUri(sharedFile)
            if (uri != null) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = sharedFile.mimeType.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "Share file via").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } else {
                onFailed()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onFailed()
        }
    }

    fun sendText(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            val text = SharedText(
                id = UUID.randomUUID().toString(),
                content = content.trim(),
                addedTime = System.currentTimeMillis(),
                senderDevice = "This Phone"
            )
            textDao.insertText(text)
        }
    }

    /**
     * Handles incoming files/media shared from Gallery, File Manager, or other apps.
     */
    fun handleSharedUrisFromIntent(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var importedCount = 0
            for (uri in uris) {
                val file = storageManager.importFromUri(uri)
                if (file != null) {
                    fileDao.insertFile(file)
                    importedCount++
                }
            }

            if (importedCount > 0) {
                withContext(Dispatchers.Main) {
                    val msg = if (importedCount == 1) {
                        "✓ 1 file added for sharing"
                    } else {
                        "✓ $importedCount files added for sharing"
                    }
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }

                // If server is not active, attempt auto-start
                if (_serverInfo.value.state != ServerState.ACTIVE && isWifiConnected.value) {
                    startServer()
                }
            }
        }
    }

    /**
     * Handles incoming text/links shared from other apps (Browser, Notes, WhatsApp, etc.).
     */
    fun handleSharedTextFromIntent(sharedText: String) {
        if (sharedText.isBlank()) return
        viewModelScope.launch {
            val text = SharedText(
                id = UUID.randomUUID().toString(),
                content = sharedText.trim(),
                addedTime = System.currentTimeMillis(),
                senderDevice = "Shared from App"
            )
            textDao.insertText(text)

            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "✓ Shared text added to LocalShare", android.widget.Toast.LENGTH_SHORT).show()
            }

            // If server is not active, attempt auto-start
            if (_serverInfo.value.state != ServerState.ACTIVE && isWifiConnected.value) {
                startServer()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        httpServer?.stop()
        LocalShareService.stopService(context)
    }
}
