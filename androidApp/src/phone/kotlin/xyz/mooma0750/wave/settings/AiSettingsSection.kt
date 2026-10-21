package xyz.mooma0750.wave.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.connect.QrScanner

/**
 * Claude の中継（ai-bridge）への接続。中継の `--pair` が出す QR（waveai://pair?u=&t=&n=）を読む（Kite と同じ）。
 */
@Composable
fun AiSettingsSection(settings: AppSettings) {
    val config by settings.aiBridge.collectAsStateWithLifecycle(initialValue = null)
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val badQr = stringResource(R.string.settings_ai_bad_qr)
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> scanning = granted }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.settings_ai_title), style = MaterialTheme.typography.titleMedium)
        Text(
            config?.let { stringResource(R.string.settings_ai_connected, it.name ?: it.url) } ?: stringResource(R.string.settings_ai_summary_off),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                message = null
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
            }) { Text(stringResource(R.string.settings_ai_scan)) }
            if (config != null) OutlinedButton(onClick = { scope.launch { settings.setAiBridge(null) } }) { Text(stringResource(R.string.settings_ai_disconnect)) }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (scanning) {
            QrScanner(onResult = { text ->
                scanning = false
                val parsed = AiBridgeConfig.parse(text)
                if (parsed == null) message = badQr else scope.launch { settings.setAiBridge(parsed) }
            }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            OutlinedButton(onClick = { scanning = false }) { Text(stringResource(R.string.connect_cancel)) }
        }
    }
}
