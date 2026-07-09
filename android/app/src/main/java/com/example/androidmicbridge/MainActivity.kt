package com.example.androidmicbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.androidmicbridge.theme.AndroidMicBridgeTheme
import android.content.Intent
import android.os.Build

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    handleIntent(intent)

    enableEdgeToEdge()
    setContent {
      AndroidMicBridgeTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
  }

  private fun handleIntent(intent: Intent?) {
    val autostart = intent?.getBooleanExtra("autostart", false) ?: false
    if (autostart) {
      val serviceIntent = Intent(this, MicStreamService::class.java).apply {
        putExtra("sampleRate", intent.getIntExtra("sampleRate", 48000))
        putExtra("isOpus", intent.getBooleanExtra("isOpus", true))
        putExtra("bitrate", intent.getIntExtra("bitrate", 64000))
        putExtra("port", intent.getIntExtra("port", MicStreamService.DEFAULT_PORT))
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(serviceIntent)
      } else {
        startService(serviceIntent)
      }
    }
  }
}
