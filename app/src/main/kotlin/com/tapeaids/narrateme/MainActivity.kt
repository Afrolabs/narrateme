package com.tapeaids.narrateme

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.RegistrationState
import com.tapeaids.narrateme.core.Mode
import com.tapeaids.narrateme.core.State
import com.tapeaids.narrateme.glasses.GlassesUpdate

class MainActivity : ComponentActivity() {
    private val vm: NarrateViewModel by viewModels()

    private val androidPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> Log.i(TAG, "Android permissions: $granted") }

    private val glassesCameraPermission = registerForActivityResult(
        Wearables.RequestPermissionContract(),
    ) { result -> Log.i(TAG, "Glasses camera permission: $result") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWearablesIntent(intent)
        androidPermissions.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT),
        )
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Screen(
                        vm = vm,
                        onRegister = { vm.register(this) },
                        onUpdate = { vm.glasses.openUpdate(this, it) },
                        onCameraPermission = { glassesCameraPermission.launch(Permission.CAMERA) },
                    )
                }
            }
        }
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

@Composable
private fun Screen(
    vm: NarrateViewModel,
    onRegister: () -> Unit,
    onUpdate: (GlassesUpdate) -> Unit,
    onCameraPermission: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val registration by vm.registration.collectAsStateWithLifecycle()
    val status by vm.glasses.status.collectAsStateWithLifecycle()
    val connected by vm.glasses.connected.collectAsStateWithLifecycle()
    val devices by vm.glasses.devices.collectAsStateWithLifecycle()
    val update by vm.glasses.updateNeeded.collectAsStateWithLifecycle()
    val idle = ui.state == State.Idle

    Column(
        Modifier
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("narrateme", style = MaterialTheme.typography.headlineMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use mock glasses", Modifier.weight(1f))
            Switch(checked = ui.useMock, onCheckedChange = vm::setUseMock, enabled = !connected)
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
            if (registration != RegistrationState.REGISTERED && !ui.useMock) {
                Button(onClick = onRegister) { Text("Register") }
            }
            Button(onClick = vm::connect, enabled = !connected) { Text("Connect") }
            OutlinedButton(onClick = onCameraPermission) { Text("Camera permission") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ui.mode == Mode.NARRATE,
                onClick = { vm.setMode(Mode.NARRATE) },
                enabled = idle,
                label = { Text("Narrate") },
            )
            FilterChip(
                selected = ui.mode == Mode.LISTEN,
                onClick = { vm.setMode(Mode.LISTEN) },
                enabled = idle,
                label = { Text("Listen") },
            )
        }

        Text("State: ${ui.state.label()}", style = MaterialTheme.typography.titleMedium)
        if (ui.message.isNotEmpty()) Text(ui.message)

        Text("Glasses buttons (on-screen stand-ins)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::pressCapture) { Text("Capture") }
            OutlinedButton(onClick = vm::pressAction) { Text("Action") }
        }

        Spacer(Modifier.height(8.dp))
        Text("Stored page", style = MaterialTheme.typography.labelLarge)
        val photo = ui.pagePhoto
        if (photo == null) {
            Text("None yet. Narrate a page first.")
        } else {
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = "Photo of the narrated page",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun State.label() = when (this) {
    State.Idle -> "Ready"
    State.TakingPhoto -> "Taking photo"
    is State.Recording -> "Recording narration"
    is State.Playing -> "Playing narration"
}
