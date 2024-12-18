package com.plcoding.recordscreen

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.provider.MediaStore
import androidx.core.content.getSystemService
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import java.io.File
import java.io.FileInputStream

@Parcelize
data class ScreenRecordConfig(
    val resultCode: Int,
    val data: Intent
): Parcelable

class ScreenRecordService: Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mediaRecorder by lazy {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(applicationContext)
        } else {
            MediaRecorder()
        }
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val outputFile by lazy {
        File(cacheDir, "tmp.mp4")
    }

    private val mediaProjectionManager by lazy {
        getSystemService<MediaProjectionManager>()
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            releaseResources()
            stopService()
            saveToGallery()
        }
    }

    private fun saveToGallery() {
        serviceScope.launch {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "video_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Recordings2")
            }
            val videoCollection = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            contentResolver.insert(videoCollection, contentValues)?.let { uri ->
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(outputFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            START_RECORDING -> {
                val notification = NotificationHelper.createNotification(applicationContext)
                NotificationHelper.createNotificationChannel(applicationContext)
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        1,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(
                        1,
                        notification
                    )
                }
                _isServiceRunning.value = true
                startRecording(intent)
            }
            STOP_RECORDING -> {
                stopRecording()
            }
        }
        return START_STICKY
    }

    private fun startRecording(intent: Intent) {
        val config = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                KEY_RECORDING_CONFIG,
                ScreenRecordConfig::class.java
            )
        } else {
            intent.getParcelableExtra(KEY_RECORDING_CONFIG,)
        }
        if(config == null) {
            return
        }

        mediaProjection = mediaProjectionManager?.getMediaProjection(
            config.resultCode,
            config.data
        )
        mediaProjection?.registerCallback(mediaProjectionCallback, null)

        initializeRecorder()
        mediaRecorder.start()

        virtualDisplay = createVirtualDisplay()
    }

    private fun stopRecording() {
        mediaRecorder.stop()
        mediaProjection?.stop()
        mediaRecorder.reset()
    }

    private fun stopService() {
        _isServiceRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun getWindowSize(): Pair<Int, Int> {
        val calculator = WindowMetricsCalculator.getOrCreate()
        val metrics = calculator.computeMaximumWindowMetrics(applicationContext)
        return metrics.bounds.width() to metrics.bounds.height()
    }

    private fun getScaledDimensions(
        maxWidth: Int,
        maxHeight: Int,
        scaleFactor: Float = 0.8f
    ): Pair<Int, Int> {
        val aspectRatio = maxWidth / maxHeight.toFloat()

        var newWidth = (maxWidth * scaleFactor).toInt()
        var newHeight = (newWidth / aspectRatio).toInt()

        if(newHeight > (maxHeight * scaleFactor)) {
            newHeight = (maxHeight * scaleFactor).toInt()
            newWidth = (newHeight * aspectRatio).toInt()
        }

        return newWidth to newHeight
    }

    private fun initializeRecorder() {
        val (width, height) = getWindowSize()
        val (scaledWidth, scaledHeight) = getScaledDimensions(
            maxWidth = width,
            maxHeight = height
        )
        with(mediaRecorder) {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setOutputFile(outputFile)
            setVideoSize(scaledWidth, scaledHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(VIDEO_BIT_RATE_KILOBITS * 1000)
            setVideoFrameRate(VIDEO_FRAME_RATE)
            prepare()
        }
    }

    private fun createVirtualDisplay(): VirtualDisplay? {
        val (width, height) = getWindowSize()
        return mediaProjection?.createVirtualDisplay(
            "Screen",
            width,
            height,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder.surface,
            null,
            null
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false
        serviceScope.coroutineContext.cancelChildren()
    }

    private fun releaseResources() {
        mediaRecorder.release()
        virtualDisplay?.release()
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()

        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_BIT_RATE_KILOBITS = 512

        const val START_RECORDING = "START_RECORDING"
        const val STOP_RECORDING = "STOP_RECORDING"
        const val KEY_RECORDING_CONFIG = "KEY_RECORDING_CONFIG"
    }
}