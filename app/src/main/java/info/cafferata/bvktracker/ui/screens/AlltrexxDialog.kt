package info.cafferata.bvktracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cafferata.bvktracker.alltrexx.AlltrexxKoppeling
import kotlinx.coroutines.launch

private const val UITLEG =
    "Als dit aan staat, wordt je positie ongeveer elke 15 seconden gedeeld op alltrexx.live, " +
        "zichtbaar voor iedereen die de kaart bekijkt. Uit = er wordt niets verstuurd."

/** Koppelen, aan/uit en ontkoppelen van live meevaren op alltrexx.live (zelfde opzet als iOS Voorkeuren). */
@Composable
fun AlltrexxDialog(onOpenLiveMap: () -> Unit, onDismiss: () -> Unit) {
    val status by AlltrexxKoppeling.status.collectAsState()
    val scope = rememberCoroutineScope()
    var naam by remember { mutableStateOf("") }
    var bezig by remember { mutableStateOf(false) }
    var fout by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Live delen op Alltrexx.live") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (status.gekoppeld) {
                    Text("Gekoppeld als: ${status.naam ?: "—"}")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Live delen")
                        Switch(checked = status.actief, onCheckedChange = { AlltrexxKoppeling.zetActief(it) })
                    }
                } else {
                    Text("Onder deze naam verschijn je op de live kaart van alltrexx.live.")
                    OutlinedTextField(
                        value = naam,
                        onValueChange = { naam = it.take(40); fout = null },
                        label = { Text("Naam van je boot") },
                        placeholder = { Text("Bijv. Zeezwaluw") },
                        singleLine = true,
                        enabled = !bezig,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                }
                fout?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(UITLEG, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenLiveMap) { Text("Bekijk live kaart") }
            }
        },
        confirmButton = {
            if (status.gekoppeld) {
                TextButton(onClick = onDismiss) { Text("Klaar") }
            } else if (bezig) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                TextButton(
                    enabled = naam.isNotBlank(),
                    onClick = {
                        bezig = true
                        scope.launch {
                            AlltrexxKoppeling.koppel(naam.trim())
                                .onFailure { fout = it.message ?: AlltrexxKoppeling.Fout.Server.message }
                            bezig = false
                        }
                    },
                ) { Text("Koppelen") }
            }
        },
        dismissButton = {
            if (status.gekoppeld) {
                TextButton(onClick = { AlltrexxKoppeling.ontkoppel() }) {
                    Text("Ontkoppelen", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss, enabled = !bezig) { Text("Annuleer") }
            }
        },
    )
}
