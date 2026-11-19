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
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

private enum class AppPage { HOME, RECORD, PAGES, GLASSES, ABOUT }

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
            AppPage.RECORD -> RecordScreen(
                vm, cameraGranted, onAskPermissions,
                onOpenPages = { page = AppPage.PAGES },
                onBack = goHome,
            )
            AppPage.PAGES -> SubScreen("Saved pages", onBack = { page = AppPage.RECORD }) {
                SavedPages(vm, onClearPages)
            }
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

/**
 * For the sighted narrator, like a camera app: the camera feed with a shutter
 * button. Taking the picture starts recording; the page photo then fills the
 * screen with a live waveform of the narrator's voice over it, and a stop button
 * at the bottom saves the narration.
 */
@Composable
private fun RecordScreen(
    vm: NarrateViewModel,
    cameraGranted: Boolean,
    onAskPermissions: () -> Unit,
    onOpenPages: () -> Unit,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val onPhone = ui.device == DeviceKind.PHONE
    LaunchedEffect(Unit) { vm.setMode(Mode.NARRATE) }
    val recording = ui.state as? State.Recording
    BackHandler {
        // Leaving mid-recording saves it, like pressing stop.
        if (recording != null) vm.pressAction()
        onBack()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (recording != null) {
            PagePhoto(recording.photo, Modifier.fillMaxSize())
            Waveform(vm, Modifier.align(Alignment.Center).fillMaxWidth().height(180.dp))
            RecordingBadge(Modifier.align(Alignment.TopCenter).padding(top = 20.dp))
        } else if (onPhone && cameraGranted) {
            CameraView(vm, Modifier.fillMaxSize())
        } else if (onPhone) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("narrateme needs the camera to see the page.", color = Color.White)
                Button(onClick = onAskPermissions) { Text("Allow camera") }
            }
        } else {
            val status by vm.glasses.status.collectAsStateWithLifecycle()
            Text(
                "Look at the page through the glasses. Glasses: $status",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                textAlign = TextAlign.Center,
            )
        }

        if (recording == null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .semantics { contentDescription = "Back" },
            ) { Text("←", fontSize = 28.sp, color = Color.White) }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (ui.message.isNotEmpty()) {
                Text(ui.message, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
            }
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                if (recording == null) {
                    LastPageButton(ui.pages.lastOrNull(), ui.pages.size, onOpenPages, Modifier.align(Alignment.CenterStart).padding(start = 24.dp))
                    ShutterButton(
                        enabled = ui.state == State.Idle,
                        onClick = vm::pressCapture,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    StopButton(onClick = vm::pressAction, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

/** The camera-app shutter: a white disc in a ring. */
@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(84.dp)
            .border(5.dp, Color.White, CircleShape)
            .padding(9.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.Gray)
            .clickable(enabled = enabled, onClickLabel = "Take picture and start recording", onClick = onClick),
    )
}

/** The video-recorder stop: a red square in a white ring. */
@Composable
private fun StopButton(onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(84.dp)
            .border(5.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(onClickLabel = "Stop recording", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE53935)))
    }
}

/** The thumbnail in the corner, like a camera app's gallery button. */
@Composable
private fun LastPageButton(last: PageThumb?, count: Int, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(2.dp, Color.White, RoundedCornerShape(8.dp))
            .background(Color.DarkGray)
            .clickable(onClickLabel = "Saved pages", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = last?.bitmap
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = "Saved pages", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text("$count", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/** The photo just taken, decoded off the main thread at screen-ish size. */
@Composable
private fun PagePhoto(photo: ByteArray, modifier: Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, photo) {
        value = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(photo, 0, photo.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1600) sample *= 2
            BitmapFactory.decodeByteArray(photo, 0, photo.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    bitmap?.let {
        Image(it.asImageBitmap(), contentDescription = "The page being narrated", contentScale = ContentScale.Fit, modifier = modifier)
    }
}

/** A live waveform of the narrator's voice: newest on the right, scrolling left. */
@Composable
private fun Waveform(vm: NarrateViewModel, modifier: Modifier) {
    val levels by vm.levels.collectAsStateWithLifecycle()
    Canvas(modifier.background(Color.Black.copy(alpha = 0.25f))) {
        val slots = 120
        val step = size.width / slots
        val mid = size.height / 2
        val offset = slots - levels.size
        levels.forEachIndexed { i, level ->
            // Square root so quiet speech still shows.
            val h = (kotlin.math.sqrt(level.coerceIn(0f, 1f)) * size.height * 0.9f).coerceAtLeast(2f)
            val x = (offset + i) * step + step / 2
            drawLine(
                color = Color(0xCC64B5F6),
                start = Offset(x, mid - h / 2),
                end = Offset(x, mid + h / 2),
                strokeWidth = step * 0.6f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** A red dot and a running clock while recording. */
@Composable
private fun RecordingBadge(modifier: Modifier) {
    val seconds by produceState(0) {
        val start = System.currentTimeMillis()
        while (true) {
            value = ((System.currentTimeMillis() - start) / 1000).toInt()
            delay(250)
        }
    }
    Row(
        modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFE53935)))
        Spacer(Modifier.size(8.dp))
        Text("%d:%02d".format(seconds / 60, seconds % 60), color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/** The saved pages, with Clear all. */
@Composable
private fun SavedPages(vm: NarrateViewModel, onClearPages: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${ui.pages.size} saved", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = onClearPages, enabled = ui.state == State.Idle && ui.pages.isNotEmpty()) { Text("Clear all") }
    }
    if (ui.pages.isEmpty()) Text("None yet. Take a picture of a page and read it aloud.")
    ui.pages.forEach { saved ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            saved.bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = "Photo of ${saved.label}", modifier = Modifier.height(120.dp))
            }
            Text(saved.label)
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
