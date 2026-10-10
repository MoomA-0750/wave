package xyz.mooma0750.wave.watchlink

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.mooma0750.wave.R

/** 時計からログインを頼まれたら、番号を出して本人に送るかを決めてもらう（ADR-0011）。アプリのどの画面の上にも出す。 */
@Composable
fun WatchLoginDialog(watchLogin: WatchLogin) {
    val pending by watchLogin.pending.collectAsStateWithLifecycle()
    val request = pending?.takeIf { watchLogin.current() === it } ?: return
    val sent = stringResource(R.string.watch_login_sent)
    val failed = stringResource(R.string.watch_login_send_failed)
    AlertDialog(
        onDismissRequest = watchLogin::decline,
        title = { Text(stringResource(R.string.watch_login_dialog_title)) },
        text = { Text(stringResource(R.string.watch_login_dialog_text, request.code)) },
        confirmButton = {
            TextButton(onClick = {
                watchLogin.approveInBackground(request, sent, failed)
            }) { Text(stringResource(R.string.watch_login_send)) }
        },
        dismissButton = { TextButton(onClick = watchLogin::decline) { Text(stringResource(R.string.watch_login_cancel)) } },
    )
}
