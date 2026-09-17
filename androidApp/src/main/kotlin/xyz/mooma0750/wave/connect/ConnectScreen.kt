package xyz.mooma0750.wave.connect

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.AppContainer

/** 端末の連携（Phase 6 の仮の画面）: 中継の状態、ペアリング、ペアの一覧（引き取る・解除）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    container: AppContainer,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val pairingState by container.pairing.state.collectAsStateWithLifecycle()
    val relayStatus by container.relayClient.status.collectAsStateWithLifecycle()
    var reload by remember { mutableIntStateOf(0) }
    var pairs by remember { mutableStateOf<List<PairStore.PairRecord>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var confirmTakeOver by remember { mutableStateOf<PairStore.PairRecord?>(null) }
    var confirmUnpair by remember { mutableStateOf<PairStore.PairRecord?>(null) }
    val takingFrom by container.connect.pendingTakeOver.collectAsStateWithLifecycle()
    // 押してから要求を出すまで（再生中かを確かめる間）に、もう一度押させない
    var checking by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }

    // 画面を離れたら、途中のペアリングは取り下げる（QR を出したまま・数字を待ったままにしない）。
    // B で数字を出している間は取り下げない（A が承認した後に戻ってきて「続ける」を押せるように）
    val latestPairingState by androidx.compose.runtime.rememberUpdatedState(pairingState)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (latestPairingState !is PairingController.State.ShowCode) container.pairing.cancel() }
    }
    var unreadable by remember { mutableStateOf(false) }
    LaunchedEffect(reload, pairingState is PairingController.State.Done) {
        pairs = container.pairStore.all()
        unreadable = container.pairStore.isUnreadable()
    }
    val fingerprint = remember { runCatching { container.deviceIdentity.fingerprintForDisplay }.getOrDefault("取得できませんでした") }
    // 結果の知らせ（引き取り・解除・相手からの解除）が来たら一覧を読み直す
    LaunchedEffect(Unit) { container.connect.events.collect { reload++ } }

    val context = LocalContext.current
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            scanning = granted
            cameraDenied = !granted
        }

    fun startTakeOver(pair: PairStore.PairRecord) {
        container.appScope.launch { container.connect.takeOver(pair.peerDeviceId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("端末の連携") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("戻る") }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Connect v2（Phase 6b、試験中）。v1 と並べて出す（L-9）
            xyz.mooma0750.wave.connect2.ConnectV2Section(container)
            if (!container.connectConfigured) {
                Text("中継の場所が設定されていないので、この機能は使えません")
                return@Column
            }
            Text("中継: ${relayStatusText(relayStatus)}", style = MaterialTheme.typography.bodyMedium)
            Text("この端末の指紋: $fingerprint", style = MaterialTheme.typography.bodySmall)
            if (unreadable) {
                Text(
                    "ペアリングした端末の情報を読めません（端末の鍵が無効になった可能性があります）。消して作り直すまで、ペアリング・引き取り・解除はできません。ログインは消えません",
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = {
                    container.appScope.launch {
                        container.pairStore.resetUnreadable()
                        reload++
                    }
                }) { Text("ペアの情報を消して作り直す") }
            }
            HorizontalDivider()

            Text("ペアリングした端末", style = MaterialTheme.typography.titleMedium)
            when (val list = pairs) {
                null -> CircularProgressIndicator()
                else ->
                    if (list.isEmpty()) {
                        Text("まだありません。下の「ペアリング」から追加します", style = MaterialTheme.typography.bodySmall)
                    } else {
                        list.forEach { pair ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(pair.peerName, Modifier.weight(1f))
                                Button(enabled = takingFrom == null && !checking, onClick = {
                                    checking = true
                                    scope.launch {
                                        try {
                                            // 手元で再生中なら、置き換える前に確かめる
                                            val playing = container.player.controller().isPlaying
                                            if (playing) confirmTakeOver = pair else startTakeOver(pair)
                                        } finally {
                                            checking = false
                                        }
                                    }
                                }) { Text("引き取る") }
                                OutlinedButton(onClick = { confirmUnpair = pair }) { Text("解除") }
                            }
                        }
                    }
            }
            takingFrom?.let { name ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("$name に引き取りを頼んでいます…")
                }
            }
            HorizontalDivider()

            Text("ペアリング", style = MaterialTheme.typography.titleMedium)
            if (cameraDenied) Text("カメラが許可されていないので QR を読み取れません。設定アプリで wave のカメラを許可してください", color = MaterialTheme.colorScheme.error)
            PairingSection(
                state = pairingState,
                scanning = scanning,
                onShowQr = { container.pairing.startOffer() },
                onScan = {
                    cameraDenied = false
                    container.pairing.cancel()
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        scanning = true
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                onScanned = { text ->
                    scanning = false
                    container.pairing.accept(text)
                },
                onEnterCode = { container.pairing.enterCode(it) },
                onContinue = { container.pairing.confirmOnB() },
                onCancel = {
                    scanning = false
                    container.pairing.cancel()
                },
            )
        }
    }

    confirmTakeOver?.let { pair ->
        AlertDialog(
            onDismissRequest = { confirmTakeOver = null },
            title = { Text("${pair.peerName} から引き取る") },
            text = { Text("この端末で再生中のキューは、${pair.peerName} のキューに置き換わります。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTakeOver = null
                    startTakeOver(pair)
                }) { Text("置き換える") }
            },
            dismissButton = { TextButton(onClick = { confirmTakeOver = null }) { Text("やめる") } },
        )
    }
    confirmUnpair?.let { pair ->
        AlertDialog(
            onDismissRequest = { confirmUnpair = null },
            title = { Text("${pair.peerName} とのペアリングを解除") },
            text = { Text("解除すると、もう一度ペアリングするまで引き取りはできません。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = null
                    container.appScope.launch { container.connect.unpair(pair.peerDeviceId) }
                }) { Text("解除する") }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = null }) { Text("やめる") } },
        )
    }
}

@Composable
private fun PairingSection(
    state: PairingController.State,
    scanning: Boolean,
    onShowQr: () -> Unit,
    onScan: () -> Unit,
    onScanned: (String) -> Unit,
    onEnterCode: (String) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    if (scanning) {
        Text("もう一方の端末に出ている QR を写してください", style = MaterialTheme.typography.bodySmall)
        QrScanner(onResult = onScanned, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        OutlinedButton(onClick = onCancel) { Text("やめる") }
        return
    }
    when (state) {
        PairingController.State.Idle -> {
            Text(
                "引き取られる側（すでに中継に登録済みの端末）で「QR を出す」、追加する端末で「QR を読み取る」を押します",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowQr) { Text("QR を出す") }
                OutlinedButton(onClick = onScan) { Text("QR を読み取る") }
            }
        }
        is PairingController.State.Working -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(state.message)
            }
            OutlinedButton(onClick = onCancel) { Text("やめる") }
        }
        is PairingController.State.ShowingOffer -> {
            Text("追加する端末の wave で「QR を読み取る」を押し、この QR を写してください（2分で無効になります）", style = MaterialTheme.typography.bodySmall)
            QrImage(state.offerText, Modifier.fillMaxWidth().aspectRatio(1f))
            OutlinedButton(onClick = onCancel) { Text("やめる") }
        }
        is PairingController.State.EnterCode -> {
            var code by remember { mutableStateOf("") }
            Text("追加する端末に出ている6桁の数字を入力してください")
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = code,
                onValueChange = { value -> code = value.filter { it.isDigit() }.take(6) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = code.length == 6, onClick = {
                    onEnterCode(code)
                    code = ""
                }) { Text("確定") }
                OutlinedButton(onClick = onCancel) { Text("やめる") }
            }
        }
        is PairingController.State.ShowCode -> {
            Text("${state.peerName} の画面に、この数字を入力してください")
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(state.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displayMedium)
            Text("入力して ${state.peerName} に「ペアリングしました」と出たら「続ける」を押します", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onContinue) { Text("続ける") }
                OutlinedButton(onClick = onCancel) { Text("やめる") }
            }
        }
        is PairingController.State.Done -> {
            Text("${state.peerName} とペアリングしました")
            OutlinedButton(onClick = onCancel) { Text("閉じる") }
        }
        is PairingController.State.Failed -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onCancel) { Text("戻る") }
        }
    }
}

private fun relayStatusText(status: RelayClient.Status): String =
    when (status) {
        RelayClient.Status.Disconnected -> "つないでいません（再生中・引き取り・ペアリングのときだけつなぎます）"
        RelayClient.Status.Connecting -> "接続しています…"
        RelayClient.Status.Connected -> "接続中"
        RelayClient.Status.TailnetUnreachable -> "届きません（Tailscale に接続しているか確かめてください）"
        RelayClient.Status.NotRegistered -> "この端末は未登録です（ペアリングすると登録されます）"
        RelayClient.Status.Failed -> "接続できませんでした"
    }
