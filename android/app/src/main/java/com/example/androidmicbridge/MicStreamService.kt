package com.example.androidmicbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class MicStreamService : Service() {

    companion object {
        private const val TAG = "MicStreamService"
        private const val CHANNEL_ID = "MicStreamServiceChannel"
        private const val NOTIFICATION_ID = 1
        const val DEFAULT_PORT = 61394
    }

    inner class LocalBinder : Binder() {
        fun getService(): MicStreamService = this@MicStreamService
    }

    private val binder = LocalBinder()

    // Configurable parameters
    var sampleRate: Int = 48000
    var isOpusEnabled: Boolean = true
    var bitrate: Int = 64000
    var port: Int = DEFAULT_PORT

    // State
    var isRunning: Boolean = false
        private set
    var clientCount: Int = 0
        private set
    var lastError: String? = null
        private set

    private var serverSocket: ServerSocket? = null
    private val activeClients = CopyOnWriteArrayList<Socket>()
    private var audioRecord: AudioRecord? = null

    // Threads and Sync
    private var serverThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var isRecording = false

    interface StatusListener {
        fun onStatusChanged()
    }

    private val listeners = CopyOnWriteArrayList<StatusListener>()

    fun registerListener(listener: StatusListener) {
        listeners.add(listener)
        listener.onStatusChanged()
    }

    fun unregisterListener(listener: StatusListener) {
        listeners.remove(listener)
    }

    private fun notifyStatusChanged() {
        listeners.forEach { it.onStatusChanged() }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sampleRate = intent?.getIntExtra("sampleRate", 48000) ?: 48000
        isOpusEnabled = intent?.getBooleanExtra("isOpus", true) ?: true
        bitrate = intent?.getIntExtra("bitrate", 64000) ?: 64000
        port = intent?.getIntExtra("port", DEFAULT_PORT) ?: DEFAULT_PORT

        startForeground(NOTIFICATION_ID, buildNotification(), 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        )

        startServer()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer() {
        if (isRunning) return
        isRunning = true
        lastError = null
        notifyStatusChanged()

        serverThread = thread(start = true, name = "MicServerThread") {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Server listening on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Log.d(TAG, "Client connected from ${socket.remoteSocketAddress}")
                    activeClients.add(socket)
                    clientCount = activeClients.size
                    notifyStatusChanged()

                    synchronized(this) {
                        if (!isRecording) {
                            startAudioCapture()
                        }
                    }
                }
            } catch (e: IOException) {
                if (isRunning) {
                    Log.e(TAG, "Server socket error", e)
                    lastError = "Socket error: ${e.message}"
                    notifyStatusChanged()
                }
            }
        }
    }

    private fun stopServer() {
        isRunning = false
        stopAudioCapture()

        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null

        activeClients.forEach {
            try {
                it.close()
            } catch (e: IOException) {
                // Ignore
            }
        }
        activeClients.clear()
        clientCount = 0
        notifyStatusChanged()

        stopSelf()
    }

    private fun startAudioCapture() {
        isRecording = true
        audioThread = thread(start = true, name = "MicAudioCaptureThread") {
            runAudioLoop()
        }
    }

    private fun stopAudioCapture() {
        isRecording = false
        audioThread?.interrupt()
        audioThread = null
    }

    private fun runAudioLoop() {
        // Calculate buffer size
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        
        // Use 20ms frame sizes
        val frameSizeInSamples = (sampleRate * 0.02).toInt()
        val frameSizeInBytes = frameSizeInSamples * 2 // 16-bit = 2 bytes per sample
        val bufferSize = maxOf(minBufferSize, frameSizeInBytes * 4)

        try {
            // Try VOICE_COMMUNICATION first for AEC/NS/AGC, fallback to MIC
            var record: AudioRecord? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (e: Exception) {
                Log.w(TAG, "VOICE_COMMUNICATION source failed, falling back to MIC", e)
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IOException("Failed to initialize AudioRecord")
            }

            audioRecord = record
            record.startRecording()
            Log.d(TAG, "Started recording: rate=$sampleRate, frameSamples=$frameSizeInSamples, size=$frameSizeInBytes")

            // Initialize Encoder if needed
            var codec: MediaCodec? = null
            var codecInputBuffers: Array<ByteBuffer>? = null
            var codecOutputBuffers: Array<ByteBuffer>? = null

            if (isOpusEnabled) {
                try {
                    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1)
                    format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                    codecInputBuffers = codec.inputBuffers
                    codecOutputBuffers = codec.outputBuffers
                    Log.d(TAG, "Initialized MediaCodec Opus encoder successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Opus encoder, falling back to raw PCM", e)
                    codec = null
                }
            }

            val pcmBuffer = ByteArray(frameSizeInBytes)
            val info = MediaCodec.BufferInfo()

            while (isRecording && activeClients.isNotEmpty()) {
                val bytesRead = record.read(pcmBuffer, 0, frameSizeInBytes)
                if (bytesRead <= 0) {
                    continue
                }

                if (codec != null) {
                    // --- OPUS ENCODING FLOW ---
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codecInputBuffers!![inputBufferIndex]
                        inputBuffer.clear()
                        inputBuffer.put(pcmBuffer, 0, bytesRead)
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            bytesRead,
                            System.nanoTime() / 1000,
                            0
                        )
                    }

                    var outputBufferIndex = codec.dequeueOutputBuffer(info, 10000)
                    while (outputBufferIndex >= 0) {
                        val outputBuffer = codecOutputBuffers!![outputBufferIndex]
                        
                        // Extract encoded Opus frame bytes
                        val outData = ByteArray(info.size)
                        outputBuffer.position(info.offset)
                        outputBuffer.get(outData)

                        // Stream packet to all clients
                        sendPacketToAll(outData)

                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        outputBufferIndex = codec.dequeueOutputBuffer(info, 0)
                    }
                } else {
                    // --- RAW PCM FLOW ---
                    // Send PCM data directly
                    val pcmData = if (bytesRead == frameSizeInBytes) pcmBuffer else pcmBuffer.copyOfRange(0, bytesRead)
                    sendPacketToAll(pcmData)
                }
            }

            // Cleanup
            record.stop()
            record.release()
            audioRecord = null

            codec?.stop()
            codec?.release()

        } catch (e: Exception) {
            Log.e(TAG, "Error in audio recording loop", e)
            lastError = "Recording error: ${e.message}"
            notifyStatusChanged()
        } finally {
            isRecording = false
        }
    }

    private fun sendPacketToAll(data: ByteArray) {
        val disconnected = mutableListOf<Socket>()
        
        for (client in activeClients) {
            try {
                if (client.isClosed || !client.isConnected) {
                    disconnected.add(client)
                    continue
                }
                val out = DataOutputStream(client.getOutputStream())
                // Write packet header: 4-byte big-endian integer representing payload length
                out.writeInt(data.size)
                // Write payload
                out.write(data)
                out.flush()
            } catch (e: IOException) {
                Log.d(TAG, "Client disconnected during write: ${client.remoteSocketAddress}")
                disconnected.add(client)
            }
        }

        if (disconnected.isNotEmpty()) {
            activeClients.removeAll(disconnected)
            clientCount = activeClients.size
            notifyStatusChanged()
            
            disconnected.forEach {
                try {
                    it.close()
                } catch (e: Exception) {}
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Android Mic Bridge Stream Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val codecName = if (isOpusEnabled) "Opus" else "PCM"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Mic Bridge")
            .setContentText("Streaming microfone ($codecName @ ${sampleRate / 1000}kHz)...")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentIntent(pendingIntent)
            .build()
    }
}
