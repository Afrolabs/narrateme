package com.tapeaids.narrateme.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.tapeaids.narrateme.core.SnapDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * The phone on its own: the back camera (CameraX), the phone mic, and on-screen
 * or volume-key buttons. In listen mode it watches the live camera view and snaps
 * a photo by itself when a page is held steady (see [SnapDetector]).
 *
 * The screen binds the camera while it's showing, see [bindCamera].
 */
class PhoneDevice(
    private val context: Context,
    private val scope: CoroutineScope,
) : NarrationDevice {
    override val kind = DeviceKind.PHONE
    override val mic = MicSource.PHONE

    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow("Camera not started")
    override val status: StateFlow<String> = _status.asStateFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // Touched only on the analysis thread, apart from the volatile flags.
    private val detector = SnapDetector()
    @Volatile private var scanning = false
    @Volatile private var resetDetector = false
    @Volatile private var capturing = false

    /**
     * Opens the back camera for [owner]'s lifecycle, with a live view on [surface]
     * if given so the user can aim at the page.
     */
    fun bindCamera(owner: LifecycleOwner, surface: Preview.SurfaceProvider?) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _status.value = "Camera permission not granted"
            return
        }
        _status.value = "Starting camera…"
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val cameras = future.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyse) }
                val preview = surface?.let { s -> Preview.Builder().build().also { it.setSurfaceProvider(s) } }
                val useCases = listOfNotNull(capture, analysis, preview).toTypedArray()
                cameras.unbindAll()
                cameras.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
                provider = cameras
                imageCapture = capture
                _connected.value = true
                _status.value = "Phone camera ready"
            }.onFailure {
                Log.w(TAG, "Camera bind failed", it)
                imageCapture = null
                _connected.value = false
                _status.value = "Phone camera unavailable: ${it.message}"
            }
        }, context.mainExecutor)
    }

    fun unbindCamera() {
        runCatching { provider?.unbindAll() }
        imageCapture = null
        _connected.value = false
        _status.value = "Camera stopped"
    }

    private fun analyse(image: ImageProxy) {
        image.use {
            if (!scanning || capturing) return
            if (resetDetector) {
                resetDetector = false
                detector.reset()
            }
            val plane = it.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining()).also { b -> buffer.get(b) }
            val small = SnapDetector.downsample(bytes, it.width, it.height, plane.rowStride, GRID_W, GRID_H)
            if (detector.offer(small)) {
                Log.i(TAG, "Page held steady, snapping")
                scope.launch(Dispatchers.Main) { if (scanning) capturePhoto() }
            }
        }
    }

    override fun startScanning(fresh: Boolean) {
        if (fresh) resetDetector = true
        scanning = true
    }

    override fun stopScanning() {
        scanning = false
        analysisExecutor.execute { detector.pause() }
    }

    override fun capturePhoto() {
        val capture = imageCapture ?: run {
            _events.tryEmit(DeviceEvent.PhotoFailed("Phone camera not ready"))
            return
        }
        if (capturing) return
        capturing = true
        val file = File(context.cacheDir, "phone_photo.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            context.mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    scope.launch(Dispatchers.IO) {
                        val event = runCatching { DeviceEvent.PhotoTaken(file.readBytes()) }
                            .getOrElse { DeviceEvent.PhotoFailed("Couldn't read photo: ${it.message}") }
                        capturing = false
                        _events.emit(event)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "Photo failed", exception)
                    capturing = false
                    _events.tryEmit(DeviceEvent.PhotoFailed("Photo failed: ${exception.message}"))
                }
            },
        )
    }

    override fun pressCapture() {
        _events.tryEmit(DeviceEvent.CapturePressed)
    }

    override fun pressAction() {
        _events.tryEmit(DeviceEvent.ActionPressed)
    }

    override fun disconnect() {
        scanning = false
        unbindCamera()
    }

    private companion object {
        const val TAG = "PhoneDevice"
        const val GRID_W = 64
        const val GRID_H = 48
    }
}
