package xyz.mooma0750.wave.connect

import xyz.mooma0750.wave.ui.ActionButtonGroup
import xyz.mooma0750.wave.ui.GroupAction
import xyz.mooma0750.wave.ui.BackButton
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
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
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.connect2.ConnectV2Section

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
    val fingerprintUnavailable = stringResource(R.string.connect_fingerprint_unavailable)
    val fingerprint = remember { runCatching { container.deviceIdentity.fingerprintForDisplay }.getOrDefault(fingerprintUnavailable) }
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
                title = { Text(stringResource(R.string.connect_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Connect v2（Phase 6b、試験中）。v1 と並べて出す（L-9）
            ConnectV2Section(container)
            if (!container.connectConfigured) {
                Text(stringResource(R.string.connect_relay_not_configured))
                return@Column
            }
            Text(stringResource(R.string.connect_relay_status, stringResource(relayStatusText(relayStatus))), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.connect_fingerprint, fingerprint), style = MaterialTheme.typography.bodySmall)
            if (unreadable) {
                Text(
                    stringResource(R.string.connect_pairs_unreadable),
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = {
                    container.appScope.launch {
                        container.pairStore.resetUnreadable()
                        reload++
                    }
                }) { Text(stringResource(R.string.connect_pairs_reset)) }
            }
            HorizontalDivider()

            Text(stringResource(R.string.connect_paired_devices), style = MaterialTheme.typography.titleMedium)
            when (val list = pairs) {
                null -> LoadingIndicator()
                else ->
                    if (list.isEmpty()) {
                        Text(stringResource(R.string.connect_no_pairs), style = MaterialTheme.typography.bodySmall)
                    } else {
                        list.forEach { pair ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(pair.peerName, Modifier.weight(1f))
                                ActionButtonGroup(
                                    listOf(
                                        GroupAction(stringResource(R.string.connect_take_over), primary = true, enabled = takingFrom == null && !checking) {
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
                                        },
                                        GroupAction(stringResource(R.string.connect_unpair)) { confirmUnpair = pair },
                                    ),
                                )
                            }
                        }
                    }
            }
            takingFrom?.let { name ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoadingIndicator()
                    Text(stringResource(R.string.connect_requesting_take_over, name))
                }
            }
            HorizontalDivider()

            Text(stringResource(R.string.connect_pairing), style = MaterialTheme.typography.titleMedium)
            if (cameraDenied) Text(stringResource(R.string.connect_camera_denied), color = MaterialTheme.colorScheme.error)
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
            title = { Text(stringResource(R.string.connect_take_over_title, pair.peerName)) },
            text = { Text(stringResource(R.string.connect_take_over_text, pair.peerName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTakeOver = null
                    startTakeOver(pair)
                }) { Text(stringResource(R.string.connect_replace)) }
            },
            dismissButton = { TextButton(onClick = { confirmTakeOver = null }) { Text(stringResource(R.string.connect_cancel)) } },
        )
    }
    confirmUnpair?.let { pair ->
        AlertDialog(
            onDismissRequest = { confirmUnpair = null },
            title = { Text(stringResource(R.string.connect_unpair_title, pair.peerName)) },
            text = { Text(stringResource(R.string.connect_unpair_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = null
                    container.appScope.launch { container.connect.unpair(pair.peerDeviceId) }
                }) { Text(stringResource(R.string.connect_unpair_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = null }) { Text(stringResource(R.string.connect_cancel)) } },
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
        Text(stringResource(R.string.connect_scan_hint), style = MaterialTheme.typography.bodySmall)
        QrScanner(onResult = onScanned, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.connect_cancel)) }
        return
    }
    when (state) {
        PairingController.State.Idle -> {
            Text(
                stringResource(R.string.connect_pairing_idle_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            ActionButtonGroup(
                listOf(
                    GroupAction(stringResource(R.string.connect_show_qr), primary = true, onClick = onShowQr),
                    GroupAction(stringResource(R.string.connect_scan_qr), onClick = onScan),
                ),
            )
        }
        is PairingController.State.Working -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LoadingIndicator()
                Text(state.message)
            }
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.connect_cancel)) }
        }
        is PairingController.State.ShowingOffer -> {
            Text(stringResource(R.string.connect_offer_hint), style = MaterialTheme.typography.bodySmall)
            QrImage(state.offerText, Modifier.fillMaxWidth().aspectRatio(1f))
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.connect_cancel)) }
        }
        is PairingController.State.EnterCode -> {
            var code by remember { mutableStateOf("") }
            Text(stringResource(R.string.connect_enter_code))
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = code,
                onValueChange = { value -> code = value.filter { it.isDigit() }.take(6) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            ActionButtonGroup(
                listOf(
                    GroupAction(stringResource(R.string.connect_confirm), primary = true, enabled = code.length == 6) {
                        onEnterCode(code)
                        code = ""
                    },
                    GroupAction(stringResource(R.string.connect_cancel), onClick = onCancel),
                ),
            )
        }
        is PairingController.State.ShowCode -> {
            Text(stringResource(R.string.connect_show_code, state.peerName))
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(state.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displayMedium)
            Text(stringResource(R.string.connect_show_code_hint, state.peerName), style = MaterialTheme.typography.bodySmall)
            ActionButtonGroup(
                listOf(
                    GroupAction(stringResource(R.string.connect_continue), primary = true, onClick = onContinue),
                    GroupAction(stringResource(R.string.connect_cancel), onClick = onCancel),
                ),
            )
        }
        is PairingController.State.Done -> {
            Text(stringResource(R.string.connect_paired_with, state.peerName))
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.connect_close)) }
        }
        is PairingController.State.Failed -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.connect_back)) }
        }
    }
}

@StringRes
private fun relayStatusText(status: RelayClient.Status): Int =
    when (status) {
        RelayClient.Status.Disconnected -> R.string.connect_relay_status_disconnected
        RelayClient.Status.Connecting -> R.string.connect_relay_status_connecting
        RelayClient.Status.Connected -> R.string.connect_relay_status_connected
        RelayClient.Status.TailnetUnreachable -> R.string.connect_relay_status_tailnet_unreachable
        RelayClient.Status.NotRegistered -> R.string.connect_relay_status_not_registered
        RelayClient.Status.Failed -> R.string.connect_relay_status_failed
    }
