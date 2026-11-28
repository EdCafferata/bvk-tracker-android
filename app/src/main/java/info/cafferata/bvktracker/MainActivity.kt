package info.cafferata.bvktracker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import info.cafferata.bvktracker.location.TrackingRepository
import info.cafferata.bvktracker.location.TrackingService
import info.cafferata.bvktracker.model.GpxFileInfo
import info.cafferata.bvktracker.store.GpxFileRepository
import info.cafferata.bvktracker.store.RecoveryStore
import info.cafferata.bvktracker.weather.WeatherService
import kotlinx.coroutines.delay
import info.cafferata.bvktracker.ui.screens.FileListScreen
import info.cafferata.bvktracker.ui.screens.TrackerScreen
import info.cafferata.bvktracker.ui.theme.BvkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName

        setContent {
            BvkTheme {
                BvkApp()
            }
        }
    }
}

@Composable
private fun BvkApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fileRepo = remember { GpxFileRepository(context) }
    remember { TrackingRepository.init(context) }
    val state by TrackingRepository.state.collectAsState()
    var showFileList by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(fileRepo.listFiles()) }
    var weather by remember { mutableStateOf<WeatherService.Weather?>(null) }

    // Herstelkopie van een rit die werd onderbroken (app afgesloten door Android, crash):
    // terugzetten als gewoon bestand, zodat er nooit een rit verloren gaat.
    LaunchedEffect(Unit) {
        val recovery = RecoveryStore(context)
        val idle = !state.isTracking && TrackingRepository.session.currentSegment.isEmpty() &&
            TrackingRepository.session.trackSegments.isEmpty()
        if (idle && recovery.exists) {
            val gpx = recovery.read()
            if (gpx != null) {
                runCatching { fileRepo.save(gpx, fileRepo.defaultFilename() + "_hersteld") }
                    .onSuccess { saved ->
                        recovery.clear()
                        files = fileRepo.listFiles()
                        Toast.makeText(context, "Onderbroken rit teruggezet: ${saved.nameWithoutExtension}", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    // Weer en waterstand Markermeer, elke 10 minuten zolang de app open is.
    LaunchedEffect(Unit) {
        while (true) {
            weather = WeatherService.fetch()
            delay(10 * 60 * 1000L)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            context.startForegroundService(Intent(context, TrackingService::class.java))
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    // System back button: from the file list go back to the tracker instead of
    // finishing the Activity (which closed the app). The tracker is the root,
    // so there back still exits as before.
    BackHandler(enabled = showFileList) { showFileList = false }

    if (showFileList) {
        FileListScreen(
            files = files,
            onBack = { showFileList = false },
            onShare = { info -> shareFile(context, info) },
            onDelete = { info -> fileRepo.delete(info); files = fileRepo.listFiles() },
        )
    } else {
        TrackerScreen(
            state = state,
            weather = weather,
            onOpenLink = { url -> openLink(context, url) },
            onToggleTracking = {
                if (state.isTracking) TrackingRepository.stop() else TrackingRepository.start()
            },
            onSave = {
                val gpx = TrackingRepository.session.exportToGpxString()
                // Alleen wissen als het opslaan echt gelukt is (iOS 2.3.0-fix: geen stil dataverlies).
                runCatching { fileRepo.save(gpx) }
                    .onSuccess { saved ->
                        TrackingRepository.reset()
                        files = fileRepo.listFiles()
                        Toast.makeText(context, "Opgeslagen: ${saved.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        Toast.makeText(context, "Opslaan mislukt. De rit blijft bewaard; probeer het opnieuw.", Toast.LENGTH_LONG).show()
                    }
            },
            onOpenFiles = {
                files = fileRepo.listFiles()
                showFileList = true
            },
        )
    }
}

private fun shareFile(context: android.content.Context, info: GpxFileInfo) {
    val uri: Uri = FileProvider.getUriForFile(context, "info.cafferata.bvktracker.fileprovider", info.file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        // "application/gpx+xml" is too specific — almost nothing registers for it
        // by exact MIME type. "*/*" lets the system show every generic file target
        // (Bluetooth, Files, Drive, mail attachments, ...), same as the iOS app's
        // plain UIActivityViewController(url:) share sheet.
        type = "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

private fun openLink(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, url, Toast.LENGTH_LONG).show() }
}
