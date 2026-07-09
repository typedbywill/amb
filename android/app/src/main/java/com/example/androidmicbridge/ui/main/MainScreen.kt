package com.example.androidmicbridge.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.example.androidmicbridge.MicStreamService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    // Bind to service on start
    DisposableEffect(Unit) {
        viewModel.bindService(context)
        onDispose {
            viewModel.unbindService(context)
        }
    }

    // Permission Launchers
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val launcherMic = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Microphone permission is required to stream audio", Toast.LENGTH_LONG).show()
        }
    }

    val launcherNotification = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            launcherMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            launcherNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Settings States (local inputs before start)
    var selectedSampleRate by remember(state.sampleRate) { mutableStateOf(state.sampleRate) }
    var isOpusSelected by remember(state.isOpusEnabled) { mutableStateOf(state.isOpusEnabled) }
    var selectedBitrate by remember(state.bitrate) { mutableStateOf(state.bitrate) }
    var selectedPort by remember(state.port) { mutableStateOf(state.port.toString()) }

    // Dropdown expanded states
    var sampleRateExpanded by remember { mutableStateOf(false) }
    var bitrateExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Title Header with Premium Gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Android Mic Bridge",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 32.sp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF00C6FF),
                            Color(0xFF0072FF)
                        )
                    )
                ),
                textAlign = TextAlign.Center
            )
        }

        Text(
            text = "Stream microphone audio to your PC with low latency",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pulsing indicator
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 0.8f,
                        targetValue = 1.2f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse"
                    )

                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .scale(if (state.isRunning) pulseScale else 1f)
                            .background(
                                color = if (state.isRunning) Color(0xFF4CAF50) else Color(0xFFE91E63),
                                shape = CircleShape
                            )
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = if (state.isRunning) "Server is Running" else "Server is Stopped",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (state.isRunning) {
                    val displayIp = state.localIp ?: "Unknown (check Wi-Fi)"
                    Text(
                        text = "IP Address: $displayIp",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Port: ${state.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Format: ${if (state.isOpusEnabled) "Opus" else "Raw PCM"} @ ${state.sampleRate / 1000} kHz",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Clients connected: ${state.clientCount}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.clientCount > 0) Color(0xFF00C6FF) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Configure parameters below and click Start",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.localIp != null) {
                        Text(
                            text = "Your Local IP: ${state.localIp}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                state.lastError?.let { err ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Configuration Section (Visible only when not running)
        AnimatedVisibility(visible = !state.isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Audio Codec Selection
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Audio Codec",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val codecs = listOf("Opus (Compressed)", "PCM (Raw)")
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            codecs.forEachIndexed { index, label ->
                                val selected = (index == 0 && isOpusSelected) || (index == 1 && !isOpusSelected)
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = codecs.size),
                                    onClick = { isOpusSelected = (index == 0) },
                                    selected = selected
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }

                    // Sample Rate Dropdown
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Sample Rate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ExposedDropdownMenuBox(
                            expanded = sampleRateExpanded,
                            onExpandedChange = { sampleRateExpanded = it }
                        ) {
                            OutlinedTextField(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                readOnly = true,
                                value = "${selectedSampleRate / 1000} kHz (${if (selectedSampleRate == 48000) "Best" else "Standard"})",
                                onValueChange = {},
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sampleRateExpanded) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            ExposedDropdownMenu(
                                expanded = sampleRateExpanded,
                                onDismissRequest = { sampleRateExpanded = false }
                            ) {
                                listOf(16000, 24000, 48000).forEach { rate ->
                                    DropdownMenuItem(
                                        text = { Text("${rate / 1000} kHz" + if (rate == 48000) " (Recommended)" else "") },
                                        onClick = {
                                            selectedSampleRate = rate
                                            sampleRateExpanded = false
                                        },
                                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                    )
                                }
                            }
                        }
                    }

                    // Bitrate Dropdown (Only for Opus)
                    AnimatedVisibility(visible = isOpusSelected) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Opus Bitrate",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ExposedDropdownMenuBox(
                                expanded = bitrateExpanded,
                                onExpandedChange = { bitrateExpanded = it }
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    readOnly = true,
                                    value = "${selectedBitrate / 1000} kbps",
                                    onValueChange = {},
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bitrateExpanded) },
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                                )
                                ExposedDropdownMenu(
                                    expanded = bitrateExpanded,
                                    onDismissRequest = { bitrateExpanded = false }
                                ) {
                                    listOf(32000, 64000, 96000, 128000).forEach { rate ->
                                        DropdownMenuItem(
                                            text = { Text("${rate / 1000} kbps" + if (rate == 64000) " (Default)" else "") },
                                            onClick = {
                                                selectedBitrate = rate
                                                bitrateExpanded = false
                                            },
                                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Port Selection
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Server Port",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = selectedPort,
                            onValueChange = { selectedPort = it.filter { char -> char.isDigit() } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Main Start/Stop Action Button
        val buttonColors = if (state.isRunning) {
            ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE91E63),
                contentColor = Color.White
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0072FF),
                contentColor = Color.White
            )
        }

        Button(
            onClick = {
                if (!hasMicPermission) {
                    launcherMic.launch(Manifest.permission.RECORD_AUDIO)
                    return@Button
                }
                
                if (state.isRunning) {
                    viewModel.stopBridge(context)
                } else {
                    val portVal = selectedPort.toIntOrNull() ?: MicStreamService.DEFAULT_PORT
                    viewModel.startBridge(
                        context = context,
                        sampleRate = selectedSampleRate,
                        isOpus = isOpusSelected,
                        bitrate = selectedBitrate,
                        port = portVal
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(30.dp),
            colors = buttonColors,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Text(
                text = if (state.isRunning) "Stop Bridge" else "Start Bridge",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            )
        }

        // Quick IP Refresh button
        if (!state.isRunning) {
            Button(
                onClick = { viewModel.refreshIp() },
                colors = ButtonDefaults.textButtonColors(),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Refresh network status")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
