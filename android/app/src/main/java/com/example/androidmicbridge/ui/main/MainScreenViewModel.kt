package com.example.androidmicbridge.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import com.example.androidmicbridge.MicStreamService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class BridgeUiState(
    val isRunning: Boolean = false,
    val clientCount: Int = 0,
    val sampleRate: Int = 48000,
    val isOpusEnabled: Boolean = true,
    val bitrate: Int = 64000,
    val port: Int = MicStreamService.DEFAULT_PORT,
    val lastError: String? = null,
    val localIp: String? = null
)

class MainScreenViewModel : ViewModel(), MicStreamService.StatusListener {

    private val _uiState = MutableStateFlow(BridgeUiState())
    val uiState: StateFlow<BridgeUiState> = _uiState.asStateFlow()

    private var micStreamService: MicStreamService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MicStreamService.LocalBinder
            val srv = binder.getService()
            micStreamService = srv
            isBound = true
            srv.registerListener(this@MainScreenViewModel)
            updateStateFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            micStreamService?.unregisterListener(this@MainScreenViewModel)
            micStreamService = null
            isBound = false
            _uiState.value = _uiState.value.copy(isRunning = false, clientCount = 0)
        }
    }

    init {
        // Find local IP address on init
        _uiState.value = _uiState.value.copy(localIp = getLocalIpAddress())
    }

    fun bindService(context: Context) {
        if (!isBound) {
            val intent = Intent(context, MicStreamService::class.java)
            // Bind to existing service if running, or just bind to it
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbindService(context: Context) {
        if (isBound) {
            micStreamService?.unregisterListener(this)
            context.unbindService(connection)
            isBound = false
            micStreamService = null
        }
    }

    fun startBridge(context: Context, sampleRate: Int, isOpus: Boolean, bitrate: Int, port: Int) {
        _uiState.value = _uiState.value.copy(
            sampleRate = sampleRate,
            isOpusEnabled = isOpus,
            bitrate = bitrate,
            port = port,
            lastError = null
        )

        val intent = Intent(context, MicStreamService::class.java).apply {
            putExtra("sampleRate", sampleRate)
            putExtra("isOpus", isOpus)
            putExtra("bitrate", bitrate)
            putExtra("port", port)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        // Bind to it immediately
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun stopBridge(context: Context) {
        val intent = Intent(context, MicStreamService::class.java)
        context.stopService(intent)
        _uiState.value = _uiState.value.copy(isRunning = false, clientCount = 0)
    }

    override fun onStatusChanged() {
        updateStateFromService()
    }

    private fun updateStateFromService() {
        val service = micStreamService ?: return
        _uiState.value = _uiState.value.copy(
            isRunning = service.isRunning,
            clientCount = service.clientCount,
            sampleRate = service.sampleRate,
            isOpusEnabled = service.isOpusEnabled,
            bitrate = service.bitrate,
            port = service.port,
            lastError = service.lastError,
            localIp = getLocalIpAddress()
        )
    }

    fun refreshIp() {
        _uiState.value = _uiState.value.copy(localIp = getLocalIpAddress())
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val inetAddress = addresses.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }
}
