package xyz.mooma0750.wave.settings

import xyz.mooma0750.wave.R
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import xyz.mooma0750.wave.ui.BackButton
import xyz.mooma0750.wave.ui.MenuIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.BuildConfig
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.session.AccountResult
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.PremiumStatus
import xyz.mooma0750.wave.core.session.SessionState
import xyz.mooma0750.wave.debug.DebugPlayback
import xyz.mooma0750.wave.debug.StreamCheck
import androidx.compose.ui.res.stringResource

/** アカウントの表示名と Premium の確認（ホームのアイコンと設定で使う）。 */
class AccountInfo(
    val account: AccountResult?,
    val premium: PremiumStatus?,
)

@Composable
fun rememberAccountInfo(
    container: AppContainer,
    obtainedAtEpochMs: Long,
): AccountInfo {
    val sessionState by container.session.state.collectAsStateWithLifecycle()
    var account by remember(obtainedAtEpochMs) { mutableStateOf<AccountResult?>(null) }
    var premium by remember(obtainedAtEpochMs) { mutableStateOf<PremiumStatus?>(null) }
    // 失敗が表示に残り続けないよう、前面に戻ったときと、セッションが Active に戻ったときに取り直す
    var refresh by remember { mutableIntStateOf(0) }
    val sessionActive = sessionState is SessionState.Active

    LifecycleResumeEffect(Unit) {
        if (account !is AccountResult.Ok || premium == PremiumStatus.Unknown) refresh++
        onPauseOrDispose {}
    }

    LaunchedEffect(obtainedAtEpochMs, refresh, sessionActive) {
        // AuthManager の LoggedIn は、WaveSession への反映より先に届くことがある。このログインが反映されるまで待つ
        container.session.appliedObtainedAtEpochMs.first { it == obtainedAtEpochMs }
        if (account is AccountResult.Ok && premium != null && premium != PremiumStatus.Unknown) return@LaunchedEffect
        val fetchedAccount = container.session.accountInfo()
        account = fetchedAccount
        // 値は出さず、種類だけを記録する（実機確認用）
        Log.i(WaveApplication.LOG_TAG, "account result=${fetchedAccount::class.simpleName} name=${(fetchedAccount as? AccountResult.Ok)?.name != null}")
        val status = container.session.premiumStatus()
        premium = status
        Log.i(WaveApplication.LOG_TAG, "premium=$status session=${container.session.state.value}")
    }
    return AccountInfo(account, premium)
}

/** 設定（Spotify の「設定とプライバシー」）。アカウント・再生履歴の送信・端末の連携・ログアウト・このアプリについて・開発用の確認。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    info: AccountInfo,
    onReLogin: () -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    val sessionState by container.session.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                ListItem(
                    headlineContent = {
                        Text(
                            when (val current = info.account) {
                                null -> stringResource(R.string.settings_account_checking)
                                is AccountResult.Ok -> current.name ?: stringResource(R.string.settings_account_no_name)
                                is AccountResult.Failed -> stringResource(R.string.settings_account_failed)
                            },
                        )
                    },
                    // 診断値: 固定の1曲で Premium 音質のフォーマットを観測できたか（再生時の音質はその曲で決める）
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.settings_premium_quality,
                                when (info.premium) {
                                    null -> stringResource(R.string.settings_premium_checking)
                                    PremiumStatus.Available -> stringResource(R.string.settings_premium_available)
                                    PremiumStatus.Unavailable -> stringResource(R.string.settings_premium_unavailable)
                                    PremiumStatus.Unknown -> stringResource(R.string.settings_premium_unknown)
                                },
                            ),
                        )
                    },
                    leadingContent = { Avatar(info, 48.dp) },
                )
            }
            when (sessionState) {
                is SessionState.Expired ->
                    item {
                        ListItem(headlineContent = { Text(stringResource(R.string.settings_relogin_required)) }, trailingContent = { Button(onClick = onReLogin) { Text(stringResource(R.string.settings_relogin)) } })
                    }
                is SessionState.Suspect ->
                    item {
                        ListItem(headlineContent = { Text(stringResource(R.string.settings_session_unverified)) }, trailingContent = { OutlinedButton(onClick = onReLogin) { Text(stringResource(R.string.settings_relogin)) } })
                    }
                else -> Unit
            }
            item { HorizontalDivider() }
            item {
                val historyEnabled by container.settings.historyEnabled.collectAsStateWithLifecycle(initialValue = null)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_history_title)) },
                    supportingContent = { Text(stringResource(R.string.settings_history_summary)) },
                    trailingContent = {
                        historyEnabled?.let { enabled ->
                            Switch(checked = enabled, onCheckedChange = { container.appScope.launch { container.settings.setHistoryEnabled(it) } })
                        }
                    },
                )
            }
            if (container.connectConfigured || container.connectV2 != null) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_connected_devices)) },
                        supportingContent = { Text(stringResource(R.string.settings_connected_devices_summary)) },
                        leadingContent = { MenuIcon(R.drawable.ic_devices) },
                        modifier = Modifier.clickable(onClick = onConnect),
                    )
                }
            }
            item {
                // 押し間違いでログインが消えないよう、確かめてからログアウトする（Spotify と同じ）
                var confirming by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_log_out)) },
                    leadingContent = { MenuIcon(R.drawable.ic_logout) },
                    modifier = Modifier.clickable { confirming = true },
                )
                if (confirming) {
                    AlertDialog(
                        onDismissRequest = { confirming = false },
                        title = { Text(stringResource(R.string.settings_log_out_confirm_title)) },
                        text = { Text(stringResource(R.string.settings_log_out_confirm_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirming = false
                                container.appScope.launch { container.logout() }
                            }) { Text(stringResource(R.string.settings_log_out)) }
                        },
                        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.settings_cancel)) } },
                    )
                }
            }
            item { HorizontalDivider() }
            item {
                // このアプリについて: 名前と版、ログインの情報の置き場所（KeystoreCredentialStore と data_extraction_rules の通り）
                ListItem(
                    overlineContent = { Text(stringResource(R.string.settings_about)) },
                    headlineContent = { Text(stringResource(R.string.app_name)) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
                            Text(stringResource(R.string.settings_login_storage), Modifier.padding(top = 4.dp))
                        }
                    },
                )
            }
            if (container.isDebuggable) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        var checkLines by remember { mutableStateOf<List<String>?>(null) }
                        val scope = rememberCoroutineScope()
                        val checking = stringResource(R.string.settings_checking)
                        OutlinedButton(onClick = {
                            checkLines = listOf(checking)
                            scope.launch { checkLines = StreamCheck.run(container) }
                        }) { Text(stringResource(R.string.settings_debug_stream_check)) }
                        checkLines?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        DebugPlaybackButtons(container)
                    }
                }
            }
        }
    }
}

/** アカウントの頭文字の丸（Spotify の左上のプロフィールのアイコン）。 */
@Composable
fun Avatar(
    info: AccountInfo,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val name = (info.account as? AccountResult.Ok)?.name
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name?.trim()?.firstOrNull()?.uppercase() ?: "",
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = if (size >= 48.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
        )
    }
}

/** debug ビルドだけの再生確認。 */
@Composable
private fun DebugPlaybackButtons(container: AppContainer) {
    val scope = rememberCoroutineScope()
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { scope.launch { container.player.playSong(DebugPlayback.fixedSong) } }) { Text(stringResource(R.string.settings_debug_play_fixed)) }
        OutlinedButton(onClick = {
            scope.launch {
                val corrupted = container.streamCache.corruptForDebug(DebugPlayback.VIDEO_ID)
                Log.i(WaveApplication.LOG_TAG, "debug corrupt cached url done=$corrupted")
                // バッファの外へ飛んで、DataSource を開き直させる
                container.player.controller().run { seekTo(currentPosition + 120_000) }
            }
        }) { Text(stringResource(R.string.settings_debug_corrupt_url)) }
        OutlinedButton(onClick = { scope.launch { container.player.addNext(DebugPlayback.unplayableSong) } }) { Text(stringResource(R.string.settings_debug_add_unplayable)) }
        OutlinedButton(onClick = {
            scope.launch {
                when (val page = container.music.playlist(DebugPlayback.PUBLIC_PLAYLIST_ID)) {
                    is CallResult.Ok ->
                        container.player.playFrom(page.value.songs, 0, DebugPlayback.PUBLIC_PLAYLIST_ID, page.value.continuation)
                    is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug playlist failed ${page.kind}")
                }
            }
        }) { Text(stringResource(R.string.settings_debug_play_public_playlist)) }
        OutlinedButton(onClick = {
            scope.launch { container.player.controller().run { seekTo(mediaItemCount - 1, 0L) } }
        }) { Text(stringResource(R.string.settings_debug_last_in_queue)) }
        OutlinedButton(onClick = {
            scope.launch { container.player.controller().run { if (duration > 10_000) seekTo(duration - 5_000) } }
        }) { Text(stringResource(R.string.settings_debug_song_end)) }
    }
}
