package com.tranthor.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tranthor.push.PushPermission
import com.tranthor.push.TranthorNotifications
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var model: ExampleModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = (application as ExampleApplication).model(intent)
        if (savedInstanceState == null) handleNotificationIntent(intent)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { ExampleScreen(model) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    /** Only the SDK's own notification PendingIntents count as opens (nonce-verified; Recents replays ignored). */
    private fun handleNotificationIntent(intent: Intent?) {
        val (notification, interaction) = TranthorNotifications.interactionFrom(this, intent) ?: return
        (application as ExampleApplication).scope.launch { model.push.receive(notification, interaction) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ExampleScreen(model: ExampleModel) {
    val push = model.push
    var showLog by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        push.refreshPermission()
    }
    LaunchedEffect(Unit) { push.refreshPermission(); model.start() }
    val enabled = model.configured && !model.configuring
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)
            .semantics { testTagsAsResourceId = true }.testTag("exampleScroll"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Tranthor Example", style = MaterialTheme.typography.headlineSmall)
        Section("Workspace")
        OutlinedTextField(model.writeKey, model::editKey, label = { Text("Write key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("writeKey"))
        OutlinedTextField(model.host, model::editHost, label = { Text("Host") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("host"))
        OutlinedTextField(model.enteredUserId, { model.enteredUserId = it }, label = { Text("User ID") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("userIdInput"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ model.launch { model.configure() } }, enabled = !model.configuring, modifier = Modifier.testTag("configure")) {
                Text(if (model.configured) "Reconfigure" else "Configure")
            }
            OutlinedButton({ model.launch { model.forgetKey() } }, enabled = !model.configuring, modifier = Modifier.testTag("forgetKey")) { Text("Forget key") }
        }
        Text(if (model.configured) "Configured" else "Not configured", Modifier.testTag("configurationStatus"))
        if (model.keyFromLaunch) Text("Launch-only key; it is not saved.", style = MaterialTheme.typography.bodySmall)

        Section("Send tagged events")
        Button({ model.launch { model.identify() } }, enabled = enabled, modifier = Modifier.testTag("identify")) { Text("Identify") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Slow flush (60 s)", Modifier.weight(1f))
            Switch(model.slowFlush, { value -> model.launch { model.setSlowFlush(value) } }, Modifier.testTag("slowFlush"))
        }
        Text("Flush interval: ${model.flushIntervalMs} ms", Modifier.testTag("flushInterval"))
        Button({ model.launch { model.send(ExampleEvent.TRACK) } }, enabled = enabled, modifier = Modifier.testTag("track")) { Text("Track (no auto flush)") }
        Text("Accepted event calls this launch: ${model.acceptedCalls}", Modifier.testTag("acceptedCalls"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ model.burstCount = maxOf(1, model.burstCount - 5) }) { Text("−5") }
            Text("Burst: ${model.burstCount}")
            OutlinedButton({ model.burstCount = minOf(1000, model.burstCount + 5) }) { Text("+5") }
        }
        Button({ model.launch { model.send(ExampleEvent.TRACK, model.burstCount) } }, enabled = enabled, modifier = Modifier.testTag("burst")) {
            Text("Track burst of ${model.burstCount}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ model.launch { model.send(ExampleEvent.PAGE) } }, enabled = enabled, modifier = Modifier.testTag("page")) { Text("Page") }
            OutlinedButton({ model.launch { model.send(ExampleEvent.SCREEN) } }, enabled = enabled, modifier = Modifier.testTag("screen")) { Text("Screen") }
        }
        Button({ model.launch { model.flush() } }, enabled = enabled && !model.flushing, modifier = Modifier.testTag("flush")) {
            Text(if (model.flushing) "Flushing…" else "Flush")
        }

        Section("Consent")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ model.launch { model.changeConsent() } }, enabled = enabled, modifier = Modifier.testTag("consent")) {
                Text(if (model.optedOut) "Opt in" else "Opt out")
            }
            OutlinedButton({ model.launch { model.reset() } }, enabled = enabled, modifier = Modifier.testTag("reset")) { Text("Reset identity") }
        }
        Text("To test offline delivery, enable Airplane mode on the device/emulator; this app never alters SDK transport.",
            style = MaterialTheme.typography.bodySmall)

        Section("Live state")
        Text("Anonymous ID: ${model.anonymousId}", style = MaterialTheme.typography.bodySmall)
        Text("User ID: ${model.userId}", style = MaterialTheme.typography.bodySmall)
        Text("Queue count: unavailable in the public SDK API", style = MaterialTheme.typography.bodySmall)
        Text("Last flush: ${model.lastFlush}", Modifier.testTag("flushResult"))
        Text("Run ID: ${model.runId}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("runId"))
        TextButton({ showLog = true }, Modifier.testTag("viewLog")) { Text("View log (${model.lines.size}/200)") }

        Section("Terminal error callbacks (${model.errors.size})")
        if (model.errors.isEmpty()) Text("None") else model.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

        Section("Push preview (optional module)")
        Text("Real FCM pushes need your Firebase options in local.properties and the Firebase key added in Tranthor under Settings > Mobile > Push. See docs/device-testing.md.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("realFcmNote"))
        Text(push.firebaseStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("firebaseStatus"))
        Text("Preview ID: ${push.previewDeliveryId}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pushPreviewId"))
        PermissionButton(push, permissionLauncher::launch)
        Button({ push.preview(withImage = false) }, enabled = enabled && !model.optedOut, modifier = Modifier.testTag("pushPreview")) { Text("Preview local notification") }
        Button({ push.preview(withImage = true) }, enabled = enabled && !model.optedOut, modifier = Modifier.testTag("pushImagePreview")) { Text("Preview notification with image") }
        Text("Permission: ${push.permission}", Modifier.testTag("pushPermissionStatus"))
        Text(push.tokenStatus, style = MaterialTheme.typography.bodySmall)
        if (BuildConfig.DEBUG && push.tokenAvailable) {
            OutlinedButton({ push.copyToken() }, modifier = Modifier.testTag("copyFcmToken")) { Text("Copy FCM token (debug)") }
        }
        Text(push.imageStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("imageHosts"))
        Text(push.registration, style = MaterialTheme.typography.bodySmall)
        Text("Push observations: ${push.observations}", Modifier.testTag("pushObservations"))
        Text(push.lastObservation, Modifier.testTag("pushLastObservation"))
    }
    if (showLog) LogDialog(model) { showLog = false }
}

@Composable
private fun PermissionButton(push: ExamplePush, request: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Button({
        if (PushPermission.shouldRequest(context)) request(PushPermission.PERMISSION) else push.refreshPermission()
    }, modifier = Modifier.testTag("pushPermission")) { Text("Request notification permission") }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun LogDialog(model: ExampleModel, dismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Live test log") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(model.lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(dismiss) { Text("Done") } },
        dismissButton = {
            TextButton({
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Tranthor log", model.lines.joinToString("\n")))
            }) { Text("Copy") }
        },
    )
}
