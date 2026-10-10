package xyz.mooma0750.wave.wear

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.BuildConfig
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.session.AccountResult
import xyz.mooma0750.wave.core.session.PremiumStatus
import xyz.mooma0750.wave.settings.AccountInfo
import xyz.mooma0750.wave.watchlink.WatchLogin

/** 時計のログイン（スマホから受け取る、ADR-0011）。スマホと同じ6桁を出して待つ。 */
@Composable
fun LoginScreen(
    watchLogin: WatchLogin,
    unreadable: Boolean,
) {
    val status by watchLogin.status.collectAsStateWithLifecycle()
    ListScreen(
        stringResource(R.string.watch_login_title),
        edgeButton = {
            MainEdgeButton(R.drawable.ic_smartphone, stringResource(if (status is WatchLogin.Status.Idle) R.string.watch_login_start else R.string.watch_login_retry), showLabel = true, onClick = watchLogin::request)
        },
    ) { look ->
        if (unreadable) item(key = "unreadable") { MessageRow(look, stringResource(R.string.login_saved_unreadable)) }
        item(key = "status") {
            when (val current = status) {
                WatchLogin.Status.Idle -> MessageRow(look, stringResource(R.string.watch_login_hint))
                WatchLogin.Status.Sending -> LoadingRow(look)
                is WatchLogin.Status.Waiting ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.watch_login_waiting), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                        Text(current.code, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                WatchLogin.Status.NoPhone -> MessageRow(look, stringResource(R.string.watch_login_no_phone))
                WatchLogin.Status.Failed -> MessageRow(look, stringResource(R.string.watch_login_failed))
                WatchLogin.Status.Done -> LoadingRow(look)
            }
        }
    }
}

/** 設定（スマホの設定と同じ項目。アカウント・再生履歴の送信・通知・端末の連携・ログアウト・このアプリについて）。 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    account: AccountInfo,
    onOpenDevices: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    val history by container.settings.historyEnabled.collectAsStateWithLifecycle(initialValue = null)
    val queueNotice by container.settings.queueNotice.collectAsStateWithLifecycle(initialValue = null)
    val releaseNotices by container.settings.releaseNotices.collectAsStateWithLifecycle(initialValue = null)
    ListScreen(stringResource(R.string.settings_title)) { look ->
        item(key = "account") {
            val name =
                when (val result = account.account) {
                    null -> stringResource(R.string.settings_account_checking)
                    is AccountResult.Ok -> result.name ?: stringResource(R.string.settings_account_no_name)
                    else -> stringResource(R.string.settings_account_failed)
                }
            val premium =
                stringResource(
                    when (account.premium) {
                        null -> R.string.settings_premium_checking
                        PremiumStatus.Available -> R.string.settings_premium_available
                        PremiumStatus.Unavailable -> R.string.settings_premium_unavailable
                        PremiumStatus.Unknown -> R.string.settings_premium_unknown
                    },
                )
            EntryRow(look, name, premium, (account.account as? AccountResult.Ok)?.thumbnailUrl, round = true) {}
        }
        item(key = "history") { Toggle(look, history, R.string.settings_history_title) { container.appScope.launch { container.settings.setHistoryEnabled(it) } } }
        item(key = "queue") { Toggle(look, queueNotice, R.string.settings_queue_notice_title) { container.appScope.launch { container.settings.setQueueNotice(it) } } }
        item(key = "releases") { Toggle(look, releaseNotices, R.string.settings_release_notices_title) { container.appScope.launch { container.settings.setReleaseNotices(it) } } }
        if (container.connectV2 != null) {
            item(key = "devices") { ActionRow(look, R.drawable.ic_devices, stringResource(R.string.settings_connected_devices), onClick = onOpenDevices) }
        }
        item(key = "logout") { ActionRow(look, R.drawable.ic_logout, stringResource(R.string.settings_log_out)) { confirmLogout = true } }
        item(key = "about") { MessageRow(look, stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) }
    }
    AlertDialog(
        visible = confirmLogout,
        onDismissRequest = { confirmLogout = false },
        title = { Text(stringResource(R.string.settings_log_out_confirm_title)) },
        text = { Text(stringResource(R.string.wear_log_out_confirm_message)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton({
                confirmLogout = false
                container.appScope.launch { container.logout() }
            })
        },
    )
}

@Composable
private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope.Toggle(
    look: RowLook,
    checked: Boolean?,
    label: Int,
    onChange: (Boolean) -> Unit,
) {
    with(look) {
        SwitchButton(
            checked = checked == true,
            onCheckedChange = onChange,
            enabled = checked != null,
            modifier = modifier(),
            transformation = transformation(),
            label = { Text(stringResource(label), maxLines = 3) },
        )
    }
}

/** 画面いっぱいの短い文（読み込み中・失敗など）。 */
@Composable
fun CenterText(text: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_music_note), null)
        Text(text, textAlign = TextAlign.Center)
    }
}
