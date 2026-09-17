package xyz.mooma0750.wave.connect2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.connect.QrImage
import xyz.mooma0750.wave.connect.QrScanner

/**
 * Connect v2 の仮の画面（Phase 6b P6b-4）。端末の連携の画面の上に出す。見た目は後で詰める（機能を先）。
 * 他の端末で再生中の曲・「ここで再生」・家に端末を加える（QR と6桁）・保存の作り直しの確認。
 */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? =
    when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
fun ConnectV2Section(container: AppContainer) {
    val manager = container.connectV2 ?: return
    val pairing = container.pairingV2 ?: return
    val scope = rememberCoroutineScope()
    val info by manager.info.collectAsStateWithLifecycle()
    val remote by manager.remote.collectAsStateWithLifecycle()
    val relayStatus by manager.relay.status.collectAsStateWithLifecycle()
    val unreadable by manager.unreadable.collectAsStateWithLifecycle()
    val pairingState by pairing.state.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    var confirmTransfer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> scanning = granted }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 裏で他の端末に止められた・再生ボタンで鳴らさなかったことを通知で知らせるため、家に入っていれば画面を開いたときに求める（2回断られたら Android が出さなくなる。Android 13 以降、レビュー p6b34 中-5）
    LaunchedEffect(info.inHousehold) {
        if (info.inHousehold && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 画面を離れたら、途中の加入を取り下げる（B で数字を出している間と、回転などで作り直すだけのときは残す、レビュー p6b34 低-5）
    DisposableEffect(Unit) {
        onDispose {
            val recreating = context.findActivity()?.isChangingConfigurations == true
            if (!recreating && pairing.state.value !is PairingV2Controller.State.ShowCode) pairing.cancel()
        }
    }

    Text("Connect v2（試験中）", style = MaterialTheme.typography.titleMedium)
    Text(
        "中継 v2: ${relayStatus.name}・家: ${if (info.inHousehold) "${info.members}台" else "入っていません"}${if (info.isWriter) "・この端末で再生中" else ""}",
        style = MaterialTheme.typography.bodySmall,
    )
    if (unreadable) {
        Text("この端末の Connect v2 の保存を読めません。作り直すと家に加わり直す必要があります", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { manager.resetUnreadable() }) { Text("作り直す") }
        return
    }
    if (info.storeResetPending) {
        Text("中継の状態が作り直されました。この状態から始めますか", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { manager.acceptStoreReset() }) { Text("この状態から始める") }
    }

    remote?.let { view ->
        val cursor = view.cursor
        val status = if (cursor.playing) "再生中" else "一時停止中"
        if (!view.accountMatches) {
            Text("別のアカウントの端末が$status（引き取れません）", style = MaterialTheme.typography.bodySmall)
        } else if (!info.isWriter) {
            Text("他の端末で$status（${cursor.positionMs / 1000}秒）")
            Button(onClick = {
                scope.launch {
                    // 手元で鳴っていれば、置き換える前に確かめる
                    if (container.player.controller().isPlaying) confirmTransfer = true else manager.transfer()
                }
            }) { Text("ここで再生") }
        }
    }

    if (!info.inHousehold && info.loaded) {
        Text("家を作れるのは、中継に最初に登録した端末だけです。ほかの端末は、家の端末が出す QR から加わります", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { manager.createHousehold() }) { Text("家を作る") }
            OutlinedButton(onClick = {
                pairing.cancel()
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
            }) { Text("QR を読み取って加わる") }
        }
    } else if (info.inHousehold) {
        OutlinedButton(onClick = { pairing.startOffer() }) { Text("家に端末を加える（QR を出す）") }
    }

    if (scanning) {
        QrScanner(onResult = { text ->
            scanning = false
            pairing.accept(text)
        }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        OutlinedButton(onClick = { scanning = false }) { Text("やめる") }
    }
    when (val state = pairingState) {
        PairingV2Controller.State.Idle -> Unit
        is PairingV2Controller.State.Working -> Text(state.message)
        is PairingV2Controller.State.ShowingOffer -> {
            Text("加える端末の wave で「QR を読み取って加わる」を押し、この QR を写してください（2分で無効）", style = MaterialTheme.typography.bodySmall)
            QrImage(state.offerText, Modifier.fillMaxWidth().aspectRatio(1f))
            OutlinedButton(onClick = { pairing.cancel() }) { Text("やめる") }
        }
        is PairingV2Controller.State.EnterCode -> {
            var code by remember { mutableStateOf("") }
            Text("加える端末に出ている6桁の数字を入力してください")
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(value = code, onValueChange = { value -> code = value.filter { it.isDigit() }.take(6) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = code.length == 6, onClick = {
                    pairing.enterCode(code)
                    code = ""
                }) { Text("確定") }
                OutlinedButton(onClick = { pairing.cancel() }) { Text("やめる") }
            }
        }
        is PairingV2Controller.State.ShowCode -> {
            Text("${state.peerName} の画面に、この数字を入力してください")
            Text(state.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displayMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pairing.confirmOnB() }) { Text("入力したら続ける") }
                OutlinedButton(onClick = { pairing.cancel() }) { Text("やめる") }
            }
        }
        is PairingV2Controller.State.Done -> {
            Text("${state.peerName} と同じ家になりました")
            OutlinedButton(onClick = { pairing.cancel() }) { Text("閉じる") }
        }
        is PairingV2Controller.State.Failed -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { pairing.cancel() }) { Text("戻る") }
        }
    }
    HorizontalDivider()

    if (confirmTransfer) {
        AlertDialog(
            onDismissRequest = { confirmTransfer = false },
            title = { Text("ここで再生") },
            text = { Text("この端末で再生中のキューは、他の端末のキューに置き換わります。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTransfer = false
                    manager.transfer()
                }) { Text("置き換える") }
            },
            dismissButton = { TextButton(onClick = { confirmTransfer = false }) { Text("やめる") } },
        )
    }
}

