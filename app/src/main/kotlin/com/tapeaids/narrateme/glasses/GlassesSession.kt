package com.tapeaids.narrateme.glasses

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceSessionError
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
import com.tapeaids.narrateme.device.DeviceEvent
import com.tapeaids.narrateme.device.DeviceKind
import com.tapeaids.narrateme.device.MicSource
import com.tapeaids.narrateme.device.NarrationDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import android.graphics.Bitmap

/** Something on the glasses side needs updating before a session can start. */
enum class GlassesUpdate { GLASSES_APP, FIRMWARE }

/**
 * Wraps the Meta Wearables Device Access Toolkit: registration, the device
 * session, button input and photo capture. With [useMock] it drives Meta's
 * Mock Device Kit instead of real glasses.
 */
class GlassesSession(
    private val context: Context,
    private val scope: CoroutineScope,
) : NarrationDevice {
    override val kind = DeviceKind.GLASSES
    override val mic = MicSource.BLUETOOTH

    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow("Not connected")
    override val status: StateFlow<String> = _status.asStateFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val registration: StateFlow<RegistrationState> get() = Wearables.registrationState

    private val _update = MutableStateFlow<GlassesUpdate?>(null)
    val updateNeeded: StateFlow<GlassesUpdate?> = _update.asStateFlow()

    private val _devices = MutableStateFlow<List<String>>(emptyList())
    /** One line per glasses the toolkit knows about, for diagnosing "no eligible device". */
    val devices: StateFlow<List<String>> = _devices.asStateFlow()

    init {
        scope.launch {
            Wearables.registrationErrorStream.collect { error ->
                Log.w(TAG, "Registration error: $error")
                _status.value = "Registration failed: $error"
            }
        }
        scope.launch {
            while (isActive) {
                val known = knownDevices()
                _devices.value = known.map { it.describe() }
                if (known.any { it.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED }) {
                    _update.value = GlassesUpdate.FIRMWARE
                }
                delay(DEVICE_POLL_MS)
            }
        }
    }

    private fun knownDevices(): List<Device> =
        Wearables.devices.value.mapNotNull { Wearables.devicesMetadata[it]?.value }

    private fun Device.describe() =
        "$name: link $linkState, ${compatibility.displayString}, worn $donState, hinge $hingeState"

    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var mockGlasses: MockGlasses? = null
    private val jobs = mutableListOf<Job>()
    private var photoJob: Job? = null

    fun register(activity: Activity) = Wearables.startRegistration(activity)

    /** Opens Meta AI on the update screen the glasses need. */
    fun openUpdate(activity: Activity, update: GlassesUpdate) {
        val result = when (update) {
            GlassesUpdate.GLASSES_APP -> Wearables.openDATGlassesAppUpdate(activity)
            GlassesUpdate.FIRMWARE -> Wearables.openFirmwareUpdate(activity)
        }
        result.errorOrNull()?.let { _status.value = "Couldn't open the update screen: $it" }
    }

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
        val sample = Uri.fromFile(samplePhotoFile())
        glasses.services.cameraCapture.setCapturedPhoto(sample)
        runCatching { glasses.services.camera.setCapturedImage(sample) }
            .onFailure { Log.w(TAG, "Mock stream photo not set", it) }
        mockGlasses = glasses
        _status.value = "Mock glasses paired"
    }

    /** Starts a session and adds button input and the photo camera. */
    fun connect() {
        if (session != null) return
        scope.launch {
            if (mockGlasses == null && Wearables.registrationState.value != RegistrationState.REGISTERED) {
                _status.value = "Not registered yet: tap Register and approve narrateme in the Meta AI app"
                return@launch
            }
            _status.value = "Looking for glasses…"
            // The device list fills in asynchronously after start-up, so give it a moment.
            val found = withTimeoutOrNull(DEVICE_WAIT_MS) {
                while (knownDevices().isEmpty()) delay(DEVICE_POLL_MS)
                true
            }
            if (found == null) {
                _status.value = "No glasses visible to the toolkit. Check they're paired in Meta AI, " +
                    "switched on and unfolded, and that narrateme is registered."
                return@launch
            }
            _status.value = "Connecting…"
            val result = Wearables.createSession(AutoDeviceSelector())
            val s = result.getOrNull() ?: run {
                _status.value = "Couldn't start a session: ${result.errorOrNull()}. Seen: " +
                    knownDevices().joinToString { it.describe() }
                return@launch
            }
            session = s
            // errors is a SharedFlow without replay: subscribe before start() or the reason is lost.
            var lastError: DeviceSessionError? = null
            jobs += launch {
                s.errors.collect { error ->
                    Log.w(TAG, "Session error: $error")
                    lastError = error
                    _status.value = "Session error: $error"
                    if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
                        _update.value = GlassesUpdate.GLASSES_APP
                    }
                }
            }
            s.start()
            val started = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                s.state.first { it == DeviceSessionState.STARTED }
            }
            if (started == null) {
                val why = lastError?.let { ", error $it" } ?: ""
                disconnect()
                _status.value = "Glasses didn't start a session (state ${s.state.value}$why). " +
                    "Wear the glasses with the arms unfolded and try again."
                return@launch
            }
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
                    is InputEvent.Capture -> _events.emit(DeviceEvent.CapturePressed)
                    is InputEvent.Button ->
                        if (event.button == ButtonType.ACTION) _events.emit(DeviceEvent.ActionPressed)
                    else -> Unit
                }
            }
        }
    }

    private fun startCamera(s: DeviceSession) {
        // Only the Stream child is used. Touching camera.photo would start the Photo child,
        // which can't run alongside a stream and returned CAPTURE_FAILURE on Oakley Meta.
        val cam = s.addCamera(STREAM_CONFIG).getOrElse {
            _status.value = "Camera unavailable: ${it.message}"
            return
        }
        camera = cam
        watchStreamErrors(cam)
    }

    private fun watchStreamErrors(cam: Camera) {
        jobs += scope.launch {
            cam.stream.errorStream.collect { error ->
                Log.w(TAG, "Stream error: $error")
                _status.value = "Camera stream error: $error"
            }
        }
    }

    /**
     * Takes one photo by briefly starting the video stream and capturing a frame at
     * full sensor resolution. The stream is stopped again so the glasses' Bluetooth
     * audio is free for recording. The result arrives on [events].
     */
    override fun capturePhoto() {
        val s = session
        if (s == null || camera == null) {
            _events.tryEmit(DeviceEvent.PhotoFailed("Glasses not connected"))
            return
        }
        if (photoJob?.isActive == true) return
        photoJob = scope.launch {
            val event = runCatching { captureFromStream(s) }
                .getOrElse { DeviceEvent.PhotoFailed("Photo crashed: ${it.message}") }
            _events.emit(event)
        }
    }

    private suspend fun captureFromStream(s: DeviceSession): DeviceEvent {
        var stream = camera?.stream ?: return DeviceEvent.PhotoFailed("Glasses not connected")
        if (stream.state.value != StreamState.STREAMING) {
            var started = stream.start()
            if (started.errorOrNull() != null) {
                // A stopped stream may not restart; rebuild the camera once and retry.
                Log.w(TAG, "Stream start failed (${started.errorOrNull()}), recreating camera")
                s.removeCamera()
                camera = null
                startCamera(s)
                stream = camera?.stream ?: return DeviceEvent.PhotoFailed("Camera unavailable")
                started = stream.start()
            }
            started.errorOrNull()?.let { return DeviceEvent.PhotoFailed("Camera stream didn't start: $it") }
            _status.value = "Starting camera…"
            val streaming = withTimeoutOrNull(STREAM_START_TIMEOUT_MS) {
                stream.state.first { it == StreamState.STREAMING }
            }
            if (streaming == null) {
                val state = stream.state.value
                stream.stop()
                return DeviceEvent.PhotoFailed("Camera stream not ready (state $state)")
            }
        }
        val result = stream.capturePhoto()
        stream.stop()
        _status.value = "Connected"
        val data = result.getOrNull()
            ?: return DeviceEvent.PhotoFailed("Photo failed: ${result.errorOrNull()}")
        val bytes = when (data) {
            is PhotoData.Bitmap -> ByteArrayOutputStream().use { out ->
                data.bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            is PhotoData.HEIC -> data.data.duplicate().let { buf ->
                buf.rewind()
                ByteArray(buf.remaining()).also { buf.get(it) }
            }
            else -> return DeviceEvent.PhotoFailed("Unknown photo format: $data")
        }
        Log.i(TAG, "Photo received: ${bytes.size} bytes ($data)")
        return DeviceEvent.PhotoTaken(bytes)
    }

    override fun startScanning(fresh: Boolean) {
        if (fresh) capturePhoto()
    }

    override fun stopScanning() = Unit

    /** On-screen stand-ins for the glasses buttons. In mock mode they go through the mock device. */
    override fun pressCapture() {
        val mock = mockGlasses
        if (mock != null) {
            mock.services.input.capture(CapturePressType.SHORT_PRESS)
        } else {
            _events.tryEmit(DeviceEvent.CapturePressed)
        }
    }

    override fun pressAction() {
        val mock = mockGlasses
        if (mock != null) {
            mock.services.input.button(ButtonType.ACTION)
        } else {
            _events.tryEmit(DeviceEvent.ActionPressed)
        }
    }

    override fun disconnect() {
        photoJob?.cancel()
        photoJob = null
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
        const val STREAM_START_TIMEOUT_MS = 10_000L
        const val JPEG_QUALITY = 92
        val STREAM_CONFIG = StreamConfiguration(videoQuality = VideoQuality.HIGH)
        const val DEVICE_WAIT_MS = 10_000L
        const val DEVICE_POLL_MS = 1_000L
    }
}
