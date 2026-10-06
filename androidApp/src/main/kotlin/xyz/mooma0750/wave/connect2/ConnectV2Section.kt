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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.connect.QrImage
import xyz.mooma0750.wave.connect.QrScanner
import xyz.mooma0750.wave.handoff.v2.Role

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
    val members by manager.members.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<ConnectV2Manager.MemberView?>(null) }
    var removeBusy by remember { mutableStateOf(false) }
    var removeMessage by remember { mutableStateOf<String?>(null) }
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

    Text(stringResource(R.string.connect_v2_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(
            R.string.connect_v2_status,
            relayStatus.name,
            if (info.inHousehold) pluralStringResource(R.plurals.connect_v2_household_members, info.members, info.members) else stringResource(R.string.connect_v2_household_none),
            if (info.isWriter) stringResource(R.string.connect_v2_status_writer_suffix) else "",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    if (unreadable) {
        Text(stringResource(R.string.connect_v2_unreadable), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { manager.resetUnreadable() }) { Text(stringResource(R.string.connect_v2_reset)) }
        return
    }
    if (info.inHousehold && relayStatus == RelayClientV2.Status.NotMember) {
        // 家から外された・中継の家が作り直された。消すかどうかは本人が決める（勝手に消さない）
        Text(stringResource(R.string.connect_v2_not_member), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { manager.forgetHousehold() }) { Text(stringResource(R.string.connect_v2_forget_household)) }
    }
    if (info.storeResetPending) {
        Text(stringResource(R.string.connect_v2_store_reset_prompt), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { manager.acceptStoreReset() }) { Text(stringResource(R.string.connect_v2_accept_store_reset)) }
    }

    remote?.let { view ->
        val cursor = view.cursor
        if (!view.accountMatches) {
            Text(
                stringResource(if (cursor.playing) R.string.connect_v2_remote_other_account_playing else R.string.connect_v2_remote_other_account_paused),
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (!info.isWriter) {
            Text(stringResource(if (cursor.playing) R.string.connect_v2_remote_playing else R.string.connect_v2_remote_paused, cursor.positionMs / 1000))
            Button(onClick = {
                scope.launch {
                    // 手元で鳴っていれば、置き換える前に確かめる
                    if (container.player.controller().isPlaying) confirmTransfer = true else manager.transfer()
                }
            }) { Text(stringResource(R.string.connect_v2_play_here)) }
        }
    }

    if (!info.inHousehold && info.loaded) {
        Text(stringResource(R.string.connect_v2_household_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { manager.createHousehold() }) { Text(stringResource(R.string.connect_v2_create_household)) }
            OutlinedButton(onClick = {
                pairing.cancel()
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
            }) { Text(stringResource(R.string.connect_v2_scan_to_join)) }
        }
    } else if (info.inHousehold) {
        OutlinedButton(onClick = { pairing.startOffer() }) { Text(stringResource(R.string.connect_v2_add_device)) }
        Text(stringResource(R.string.connect_v2_household_devices), style = MaterialTheme.typography.bodyMedium)
        for (member in members) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (member.role == Role.Controller) stringResource(R.string.connect_v2_member_controller, member.label) else member.label, modifier = Modifier.weight(1f))
                if (!member.isSelf) {
                    TextButton(enabled = !removeBusy, onClick = { removing = member }) { Text(stringResource(R.string.connect_v2_remove)) }
                }
            }
        }
        removeMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    if (scanning) {
        QrScanner(onResult = { text ->
            scanning = false
            pairing.accept(text)
        }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        OutlinedButton(onClick = { scanning = false }) { Text(stringResource(R.string.connect_cancel)) }
    }
    when (val state = pairingState) {
        PairingV2Controller.State.Idle -> Unit
        is PairingV2Controller.State.Working -> Text(state.message)
        is PairingV2Controller.State.ShowingOffer -> {
            Text(stringResource(R.string.connect_v2_offer_hint), style = MaterialTheme.typography.bodySmall)
            QrImage(state.offerText, Modifier.fillMaxWidth().aspectRatio(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // カメラの無い PC で加えるとき: QR と同じ文字列を共有して、PC の wave に貼り付けてもらう（秘密は載っていない。照合は6桁で行う）
                OutlinedButton(onClick = {
                    val send =
                        android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, state.offerText)
                    context.startActivity(android.content.Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.connect_v2_share_offer)) }
                OutlinedButton(onClick = { pairing.cancel() }) { Text(stringResource(R.string.connect_cancel)) }
            }
        }
        is PairingV2Controller.State.EnterCode -> {
            var code by remember { mutableStateOf("") }
            Text(stringResource(R.string.connect_v2_enter_code))
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(value = code, onValueChange = { value -> code = value.filter { it.isDigit() }.take(6) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = code.length == 6, onClick = {
                    pairing.enterCode(code)
                    code = ""
                }) { Text(stringResource(R.string.connect_confirm)) }
                OutlinedButton(onClick = { pairing.cancel() }) { Text(stringResource(R.string.connect_cancel)) }
            }
        }
        is PairingV2Controller.State.ShowCode -> {
            Text(stringResource(R.string.connect_show_code, state.peerName))
            Text(state.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displayMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pairing.confirmOnB() }) { Text(stringResource(R.string.connect_v2_continue_after_entering)) }
                OutlinedButton(onClick = { pairing.cancel() }) { Text(stringResource(R.string.connect_cancel)) }
            }
        }
        is PairingV2Controller.State.Done -> {
            Text(stringResource(R.string.connect_v2_joined, state.peerName))
            OutlinedButton(onClick = { pairing.cancel() }) { Text(stringResource(R.string.connect_close)) }
        }
        is PairingV2Controller.State.Failed -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { pairing.cancel() }) { Text(stringResource(R.string.connect_back)) }
        }
    }
    HorizontalDivider()

    removing?.let { member ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.connect_v2_remove_title)) },
            text = { Text(stringResource(R.string.connect_v2_remove_text, member.label)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    removeBusy = true
                    removeMessage = null
                    scope.launch {
                        val removed = manager.removeMember(member.deviceId)
                        removeBusy = false
                        removeMessage = if (removed) context.getString(R.string.connect_v2_removed, member.label) else context.getString(R.string.connect_v2_remove_failed)
                    }
                }) { Text(stringResource(R.string.connect_v2_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.connect_cancel)) } },
        )
    }

    if (confirmTransfer) {
        AlertDialog(
            onDismissRequest = { confirmTransfer = false },
            title = { Text(stringResource(R.string.connect_v2_play_here)) },
            text = { Text(stringResource(R.string.connect_v2_play_here_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTransfer = false
                    manager.transfer()
                }) { Text(stringResource(R.string.connect_replace)) }
            },
            dismissButton = { TextButton(onClick = { confirmTransfer = false }) { Text(stringResource(R.string.connect_cancel)) } },
        )
    }
}

