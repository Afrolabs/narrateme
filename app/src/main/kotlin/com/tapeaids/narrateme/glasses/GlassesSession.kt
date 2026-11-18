package com.tapeaids.narrateme.glasses

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.photo.types.PhotoQuality
import com.meta.wearable.dat.camera.photo.types.PhotoResolution
import com.meta.wearable.dat.camera.photo.types.PhotoState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.ButtonType
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.camera.removeCamera
import com.tapeaids.narrateme.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** What the glasses tell the app. */
sealed interface GlassesEvent {
    data object CapturePressed : GlassesEvent
    data object ActionPressed : GlassesEvent
    class PhotoTaken(val bytes: ByteArray) : GlassesEvent
    data class PhotoFailed(val reason: String) : GlassesEvent
}

/**
 * Wraps the Meta Wearables Device Access Toolkit: registration, the device
 * session, button input and photo capture. With [useMock] it drives Meta's
 * Mock Device Kit instead of real glasses.
 */
class GlassesSession(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<GlassesEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val registration: StateFlow<RegistrationState> get() = Wearables.registrationState

    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var mockGlasses: MockGlasses? = null
    private val jobs = mutableListOf<Job>()

    fun initialize() {
        Wearables.initialize(context).onFailure { error, _ ->
            _status.value = "Toolkit init failed: $error"
        }
    }

    fun register(activity: Activity) = Wearables.startRegistration(activity)

    fun enableMock(enabled: Boolean) {
        val kit = MockDeviceKit.getInstance(context)
        if (!enabled) {
            disconnect()
            kit.disable()
            mockGlasses = null
            return
        }
        kit.enable(MockDeviceKitConfig(true, true))
        val glasses = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
        glasses.powerOn()
        glasses.don()
        glasses.unfold()
        glasses.services.cameraCapture.setCapturedPhoto(Uri.fromFile(samplePhotoFile()))
        mockGlasses = glasses
        _status.value = "Mock glasses paired"
    }

    /** Starts a session and adds button input and the photo camera. */
    fun connect() {
        if (session != null) return
        scope.launch {
            _status.value = "Connecting…"
            val s = Wearables.createSession(AutoDeviceSelector()).getOrElse {
                _status.value = "No glasses: ${it.message}"
                return@launch
            }
            session = s
            s.start()
            val started = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                s.state.first { it == DeviceSessionState.STARTED }
            }
            if (started == null) {
                _status.value = "Glasses didn't start a session (state ${s.state.value})"
                disconnect()
                return@launch
            }
            jobs += launch { s.errors.collect { Log.w(TAG, "Session error: $it") } }
            jobs += launch {
                s.state.collect { state ->
                    if (state == DeviceSessionState.STOPPED) {
                        _connected.value = false
                        _status.value = "Session stopped"
                    }
                }
            }
            startInputs(s)
            startCamera(s)
            _connected.value = true
            _status.value = "Connected"
        }
    }

    private fun startInputs(s: DeviceSession) {
        val inputs = s.addInputs(
            InputsConfiguration(setOf(InputSource.CAPTURE_BUTTON, InputSource.ACTION_BUTTON)),
        ).getOrElse {
            _status.value = "Button input unavailable: ${it.message}"
            return
        }
        jobs += scope.launch {
            inputs.events.collect { event ->
                when (event) {
                    is InputEvent.Capture -> _events.emit(GlassesEvent.CapturePressed)
                    is InputEvent.Button ->
                        if (event.button == ButtonType.ACTION) _events.emit(GlassesEvent.ActionPressed)
                    else -> Unit
                }
            }
        }
    }

    private fun startCamera(s: DeviceSession) {
        val cam = s.addCamera().getOrElse {
            _status.value = "Camera unavailable: ${it.message}"
            return
        }
        camera = cam
        // Subscribe before the photo child starts, as Meta's docs recommend.
        val photo = cam.photo
        jobs += scope.launch {
            photo.photoStream.collect { _events.emit(GlassesEvent.PhotoTaken(it.imageData)) }
        }
        jobs += scope.launch {
            photo.errors.collect { _events.emit(GlassesEvent.PhotoFailed(it.description)) }
        }
    }

    /** Requests one full-resolution photo; the result arrives on [events]. */
    fun capturePhoto() {
        val photo = camera?.photo
        if (photo == null) {
            _events.tryEmit(GlassesEvent.PhotoFailed("Glasses not connected"))
            return
        }
        scope.launch {
            // The photo child starts lazily on first access; give it a moment before failing.
            val ready = withTimeoutOrNull(PHOTO_READY_TIMEOUT_MS) {
                photo.state.first { it == PhotoState.STARTED }
            }
            if (ready == null) {
                _events.emit(GlassesEvent.PhotoFailed("Camera not ready"))
            } else {
                photo.capturePhoto(PhotoResolution.FULL, PhotoQuality.HIGH)
            }
        }
    }

    /** On-screen stand-ins for the glasses buttons. In mock mode they go through the mock device. */
    fun pressCapture() {
        val mock = mockGlasses
        if (mock != null) {
            mock.services.input.capture(CapturePressType.SHORT_PRESS)
        } else {
            _events.tryEmit(GlassesEvent.CapturePressed)
        }
    }

    fun pressAction() {
        val mock = mockGlasses
        if (mock != null) {
            mock.services.input.button(ButtonType.ACTION)
        } else {
            _events.tryEmit(GlassesEvent.ActionPressed)
        }
    }

    fun disconnect() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        session?.let { s ->
            camera?.let { s.removeCamera() }
            s.removeInputs()
            s.stop()
        }
        camera = null
        session = null
        _connected.value = false
    }

    private fun samplePhotoFile(): File {
        val file = File(context.filesDir, "mock_page.jpg")
        if (!file.exists()) {
            context.resources.openRawResource(R.raw.sample_page).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        return file
    }

    private companion object {
        const val TAG = "GlassesSession"
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val PHOTO_READY_TIMEOUT_MS = 5_000L
    }
}
