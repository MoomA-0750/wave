package xyz.mooma0750.wave.wear

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Stepper
import androidx.wear.compose.material3.Text
import xyz.mooma0750.wave.R

/** 音量（＋−とベゼル）と、出力先（Bluetooth のイヤホン）の切り替え。 */
@Composable
fun VolumeScreen() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    Stepper(
        value = volume,
        onValueChange = {
            volume = it.coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        },
        valueProgression = 0..max,
        decreaseIcon = { Icon(painterResource(R.drawable.ic_volume_down), stringResource(R.string.wear_volume_down)) },
        increaseIcon = { Icon(painterResource(R.drawable.ic_volume_up), stringResource(R.string.wear_volume_up)) },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$volume / $max")
            Button(onClick = { openOutputSwitcher(context) }, label = { Text(stringResource(R.string.wear_output)) }, icon = { Icon(painterResource(R.drawable.ic_bluetooth), null) })
        }
    }
}

/** 出力先（Bluetooth）を選ぶ画面を開く（Wear OS の決まりの開き方）。 */
fun openOutputSwitcher(context: Context) {
    val intent =
        Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("EXTRA_CONNECTION_ONLY", true)
            .putExtra("EXTRA_CLOSE_ON_CONNECT", true)
            .putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", 1)
    runCatching { context.startActivity(intent) }
}
