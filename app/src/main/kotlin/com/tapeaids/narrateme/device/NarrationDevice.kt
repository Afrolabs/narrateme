package com.tapeaids.narrateme.device

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Which hardware the narrator or listener is using. */
enum class DeviceKind { PHONE, GLASSES }

/** Where narration is recorded from. */
enum class MicSource {
    /** The phone's own microphone. */
    PHONE,

    /** A Bluetooth headset mic over HFP/SCO, as the glasses expose theirs. */
    BLUETOOTH,
}

/** What a device tells the app: button presses and photo results. */
sealed interface DeviceEvent {
    data object CapturePressed : DeviceEvent
    data object ActionPressed : DeviceEvent
    class PhotoTaken(val bytes: ByteArray) : DeviceEvent
    data class PhotoFailed(val reason: String) : DeviceEvent
}

/**
 * The input/output the narrate/listen loop needs: a camera, a microphone, a
 * capture button and an action button. Audio out (narration and spoken prompts)
 * goes to Android's media route, which is the glasses when they're connected over
 * Bluetooth and the phone speaker otherwise.
 *
 * The phone alone is a complete device; the glasses are a hands-free upgrade.
 */
interface NarrationDevice {
    val kind: DeviceKind
    val mic: MicSource

    val events: SharedFlow<DeviceEvent>
    val status: StateFlow<String>

    /** True when [capturePhoto] can work. */
    val connected: StateFlow<Boolean>

    /** Takes one photo; the result arrives on [events] as PhotoTaken or PhotoFailed. */
    fun capturePhoto()

    /**
     * Listen mode: watch for a page and snap it, answering with PhotoTaken. The phone
     * does this from its live camera view. The glasses snap only on [fresh] (when
     * listening starts) and otherwise wait for a capture press.
     */
    fun startScanning(fresh: Boolean)
    fun stopScanning()

    /** On-screen (or volume key) stand-ins for the device's buttons. */
    fun pressCapture()
    fun pressAction()

    fun disconnect()
}
