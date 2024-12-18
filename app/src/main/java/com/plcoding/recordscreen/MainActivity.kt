package com.plcoding.recordscreen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plcoding.recordscreen.ScreenRecordService.Companion.KEY_RECORDING_CONFIG
import com.plcoding.recordscreen.ScreenRecordService.Companion.START_RECORDING
import com.plcoding.recordscreen.ScreenRecordService.Companion.STOP_RECORDING
import com.plcoding.recordscreen.ui.theme.CoralRed
import com.plcoding.recordscreen.ui.theme.MintGreen
import com.plcoding.recordscreen.ui.theme.RecordScreenTheme

class MainActivity : ComponentActivity() {

    private val mediaProjectionManager by lazy {
        getSystemService<MediaProjectionManager>()!!
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RecordScreenTheme {
                val isServiceRunning by ScreenRecordService
                    .isServiceRunning
                    .collectAsStateWithLifecycle()

                var hasNotificationPermission by remember {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                applicationContext,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                    } else mutableStateOf(true)
                }
                val screenRecordLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val intent = result.data ?: return@rememberLauncherForActivityResult
                    val config = ScreenRecordConfig(
                        resultCode = result.resultCode,
                        data = intent
                    )

                    val serviceIntent = Intent(
                        applicationContext,
                        ScreenRecordService::class.java
                    ).apply {
                        action = START_RECORDING
                        putExtra(KEY_RECORDING_CONFIG, config)
                    }
                    startForegroundService(serviceIntent)
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    hasNotificationPermission = isGranted
                    if (hasNotificationPermission && !isServiceRunning) {
                        screenRecordLauncher.launch(
                            mediaProjectionManager.createScreenCaptureIntent()
                        )
                    }
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = {
                                if (!hasNotificationPermission &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                ) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    if (isServiceRunning) {
                                        Intent(
                                            applicationContext,
                                            ScreenRecordService::class.java
                                        ).also {
                                            it.action = STOP_RECORDING
                                            startForegroundService(it)
                                        }
                                    } else {
                                        screenRecordLauncher.launch(
                                            mediaProjectionManager.createScreenCaptureIntent()
                                        )
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isServiceRunning) {
                                    CoralRed
                                } else MintGreen
                            )
                        ) {
                            Text(
                                text = if (isServiceRunning) {
                                    "Stop recording"
                                } else "Start recording",
                                color = Color.Black
                            )
                        }
                    }
                }
            }
        }
    }
}