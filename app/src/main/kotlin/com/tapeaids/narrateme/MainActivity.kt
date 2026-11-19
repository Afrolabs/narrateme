package com.tapeaids.narrateme

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.RegistrationState
import com.tapeaids.narrateme.core.Mode
import com.tapeaids.narrateme.core.State
import com.tapeaids.narrateme.device.DeviceKind
import com.tapeaids.narrateme.glasses.GlassesUpdate

class MainActivity : ComponentActivity() {
    private val vm: NarrateViewModel by viewModels()

    private var cameraGranted by mutableStateOf(false)

    private val androidPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        Log.i(TAG, "Android permissions: $granted")
        cameraGranted = hasCameraPermission()
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() = androidPermissions.launch(
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT),
    )

    private val glassesCameraPermission = registerForActivityResult(
        Wearables.RequestPermissionContract(),
    ) { result -> Log.i(TAG, "Glasses camera permission: $result") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWearablesIntent(intent)
        cameraGranted = hasCameraPermission()
        requestPermissions()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Screen(
                        vm = vm,
                        cameraGranted = cameraGranted,
                        onAskPermissions = ::requestPermissions,
                        onRegister = { vm.register(this) },
                        onUpdate = { vm.glasses.openUpdate(this, it) },
                        onCameraPermission = { glassesCameraPermission.launch(Permission.CAMERA) },
                        onClearPages = vm::clearPages,
                    )
                }
            }
        }
    }

    /**
     * With the phone as the device, volume up is the capture button and volume down
     * the action button, so the app works without looking at the screen.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (vm.ui.value.device == DeviceKind.PHONE) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.repeatCount == 0) vm.pressCapture()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.repeatCount == 0) vm.pressAction()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWearablesIntent(intent)
    }

    /** Lets the toolkit finish registration/permission flows that return from the Meta AI app. */
    private fun handleWearablesIntent(intent: Intent?) {
        intent ?: return
        Wearables.handleIntent(intent) { request -> Log.i(TAG, "Registration request: $request") }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

private enum class AppPage { HOME, RECORD, GLASSES, ABOUT }

@Composable
private fun Screen(
    vm: NarrateViewModel,
    cameraGranted: Boolean,
    onAskPermissions: () -> Unit,
    onRegister: () -> Unit,
    onUpdate: (GlassesUpdate) -> Unit,
    onCameraPermission: () -> Unit,
    onClearPages: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(AppPage.HOME) }
    val goHome = {
        vm.setMode(Mode.LISTEN)
        page = AppPage.HOME
    }
    Box(Modifier.safeDrawingPadding()) {
        when (page) {
            AppPage.HOME -> HomeScreen(vm, cameraGranted, onAskPermissions, onOpen = { page = it })
            AppPage.RECORD -> RecordScreen(vm, cameraGranted, onAskPermissions, onClearPages, onBack = goHome)
            AppPage.GLASSES -> SubScreen("Glasses", onBack = goHome) {
                GlassesPanel(vm, onRegister, onUpdate, onCameraPermission)
            }
            AppPage.ABOUT -> SubScreen("About", onBack = goHome) { About() }
        }
    }
}

/** The main call to action: one full-screen Listen to pages button, and a menu in the corner. */
@Composable
private fun HomeScreen(
    vm: NarrateViewModel,
    cameraGranted: Boolean,
    onAskPermissions: () -> Unit,
    onOpen: (AppPage) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listening = ui.mode == Mode.LISTEN && ui.state != State.Idle
    val onPhone = ui.device == DeviceKind.PHONE

    if (listening) {
        ListeningScreen(vm, onPhone)
        return
    }
    Box(Modifier.fillMaxSize()) {
        Button(
            onClick = {
                if (onPhone && !cameraGranted) onAskPermissions() else vm.startListening()
            },
            modifier = Modifier.fillMaxSize().padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📖", fontSize = 120.sp)
                Spacer(Modifier.height(16.dp))
                Text("Listen to pages", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                if (ui.message.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(ui.message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
            }
        }
        Menu(Modifier.align(Alignment.TopEnd).padding(20.dp), onOpen)
    }
}

@Composable
private fun Menu(modifier: Modifier, onOpen: (AppPage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { open = true },
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .semantics { contentDescription = "Menu" },
        ) { Text("☰", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Record narration") }, onClick = { open = false; onOpen(AppPage.RECORD) })
            DropdownMenuItem(text = { Text("Glasses") }, onClick = { open = false; onOpen(AppPage.GLASSES) })
            DropdownMenuItem(text = { Text("About") }, onClick = { open = false; onOpen(AppPage.ABOUT) })
        }
    }
}

/**
 * While listening: the camera view fills the screen and a tap anywhere pauses or
 * resumes the narration. Back (or volume down) stops listening.
 */
@Composable
private fun ListeningScreen(vm: NarrateViewModel, onPhone: Boolean) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackHandler { vm.stopListening() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (onPhone) CameraView(vm, Modifier.fillMaxSize())
        // A layer over the camera view takes the taps, so the preview can't swallow them.
        Box(Modifier.fillMaxSize().clickable(onClickLabel = "Pause or play") { vm.tapScreen() })
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(ui.state.label(), color = Color.White, style = MaterialTheme.typography.headlineSmall)
            if (ui.message.isNotEmpty()) Text(ui.message, color = Color.White, textAlign = TextAlign.Center)
            if (ui.lastMatch.isNotEmpty()) {
                Text(ui.lastMatch, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = vm::stopListening) { Text("Stop listening", color = Color.White) }
        }
    }
}

/** Live view from the phone's back camera; the camera runs while this is on screen. */
@Composable
private fun CameraView(vm: NarrateViewModel, modifier: Modifier) {
    val owner = LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx -> PreviewView(ctx).also { vm.phone.bindCamera(owner, it.surfaceProvider) } },
        modifier = modifier,
    )
    DisposableEffect(owner) {
        onDispose { vm.phone.unbindCamera() }
    }
}

@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Text("←", fontSize = 26.sp)
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        content()
    }
}

/** For the sighted narrator: aim at the page, Capture to start recording, Stop to save. */
@Composable
private fun RecordScreen(
    vm: NarrateViewModel,
    cameraGranted: Boolean,
    onAskPermissions: () -> Unit,
    onClearPages: () -> Unit,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val idle = ui.state == State.Idle
    val onPhone = ui.device == DeviceKind.PHONE
    LaunchedEffect(Unit) { vm.setMode(Mode.NARRATE) }
    val back = {
        // Leaving mid-recording saves it, like pressing Stop.
        if (ui.state is State.Recording) vm.pressAction()
        onBack()
    }
    SubScreen("Record narration", onBack = back) {
        if (onPhone) {
            if (cameraGranted) {
                CameraView(vm, Modifier.fillMaxWidth().height(320.dp))
            } else {
                Text("narrateme needs the camera to see the page.")
                Button(onClick = onAskPermissions) { Text("Allow camera") }
            }
        } else {
            val status by vm.glasses.status.collectAsStateWithLifecycle()
            Text("Using the glasses: $status")
        }

        Text(ui.state.label(), style = MaterialTheme.typography.titleMedium)
        if (ui.message.isNotEmpty()) Text(ui.message)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::pressCapture, modifier = Modifier.weight(1f).height(72.dp)) {
                Text("Capture", style = MaterialTheme.typography.titleLarge)
            }
            OutlinedButton(onClick = vm::pressAction, modifier = Modifier.weight(1f).height(72.dp)) {
                Text("Stop", style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            if (onPhone) "Volume up = Capture, volume down = Stop" else "Or use the glasses' capture and action buttons",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Saved pages (${ui.pages.size})", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = onClearPages, enabled = idle && ui.pages.isNotEmpty()) { Text("Clear all") }
        }
        if (ui.pages.isEmpty()) {
            Text("None yet. Capture a page and read it aloud.")
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ui.pages.forEach { saved ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        saved.bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Photo of ${saved.label}",
                                modifier = Modifier.height(140.dp),
                            )
                        }
                        Text(saved.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun About() {
    Text(
        "narrateme lets a sighted narrator photograph a page and read it aloud. " +
            "Later, anyone can point the phone at that page and hear it in the narrator's voice.",
    )
    Text("Listen: tap Listen to pages and hold the phone over a page. It snaps the page by itself " +
        "when you hold still. Tap anywhere to pause or carry on. Back or volume down stops.")
    Text("Record narration: aim at the page, press Capture (or volume up), read the page aloud, " +
        "then press Stop (or volume down).")
    Text("Meta glasses can stand in for the phone's camera, mic and buttons: see Glasses in the menu.")
    Text("Made for tapeaids.com", style = MaterialTheme.typography.bodySmall)
    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
}

/** Registration, connection and diagnostics for the Meta glasses. */
@Composable
private fun GlassesPanel(
    vm: NarrateViewModel,
    onRegister: () -> Unit,
    onUpdate: (GlassesUpdate) -> Unit,
    onCameraPermission: () -> Unit,
) {
    val registration by vm.registration.collectAsStateWithLifecycle()
    val status by vm.glasses.status.collectAsStateWithLifecycle()
    val connected by vm.glasses.connected.collectAsStateWithLifecycle()
    val devices by vm.glasses.devices.collectAsStateWithLifecycle()
    val update by vm.glasses.updateNeeded.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val useMock = ui.useMock

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Use the glasses instead of the phone", Modifier.weight(1f))
        Switch(
            checked = ui.device == DeviceKind.GLASSES,
            onCheckedChange = { vm.setDevice(if (it) DeviceKind.GLASSES else DeviceKind.PHONE) },
            enabled = ui.state == State.Idle,
        )
    }
    Text(
        "The glasses become the camera, mic and buttons: capture to snap a page, action to stop. " +
            "Narration plays in the glasses whenever they're connected over Bluetooth.",
        style = MaterialTheme.typography.bodySmall,
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Use mock glasses", Modifier.weight(1f))
        Switch(checked = useMock, onCheckedChange = vm::setUseMock, enabled = !connected)
    }

    Text("Registration: $registration")
    Text("Glasses: $status")
    if (devices.isEmpty()) {
        Text("Devices: none seen yet", style = MaterialTheme.typography.bodySmall)
    } else {
        devices.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
    update?.let { needed ->
        Button(onClick = { onUpdate(needed) }) {
            Text(if (needed == GlassesUpdate.FIRMWARE) "Update glasses firmware" else "Update glasses app")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (registration != RegistrationState.REGISTERED && !useMock) {
            Button(onClick = onRegister) { Text("Register") }
        }
        Button(onClick = vm::connect, enabled = !connected) { Text("Connect") }
        OutlinedButton(onClick = onCameraPermission) { Text("Camera permission") }
    }
}

private fun State.label() = when (this) {
    State.Idle -> "Ready"
    State.TakingPhoto -> "Taking photo"
    State.Matching -> "Finding page"
    State.Scanning -> "Looking for a page"
    is State.Recording -> "Recording narration"
    is State.Playing -> "Reading"
    is State.Paused -> "Paused"
}
