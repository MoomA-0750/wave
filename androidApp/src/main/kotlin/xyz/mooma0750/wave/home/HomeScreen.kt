package xyz.mooma0750.wave.home

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import xyz.mooma0750.wave.WaveApplication
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import xyz.mooma0750.wave.debug.DebugPlayback
import xyz.mooma0750.wave.nowplaying.MiniPlayer
import xyz.mooma0750.wave.debug.StreamCheck
import xyz.mooma0750.wave.core.session.AccountResult
import xyz.mooma0750.wave.core.session.PremiumStatus
import xyz.mooma0750.wave.core.session.SessionState

@Composable
fun HomeScreen(
    container: AppContainer,
    obtainedAtEpochMs: Long,
    onReLogin: () -> Unit,
    onSearch: () -> Unit,
) {
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

    Scaffold(bottomBar = { MiniPlayer(container.player) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                when (val current = account) {
                    null -> "アカウントを確認しています…"
                    is AccountResult.Ok -> current.name ?: "名前を取得できませんでした"
                    is AccountResult.Failed -> "アカウントを取得できませんでした"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            // 診断値: 固定の1曲で Premium 音質のフォーマットを観測できたか（再生時の音質はその曲で決める）
            Text(
                "Premium 音質: " +
                    when (premium) {
                        null -> "確認中…"
                        PremiumStatus.Available -> "利用可"
                        PremiumStatus.Unavailable -> "利用不可"
                        PremiumStatus.Unknown -> "未確認"
                    },
            )
            Button(onClick = onSearch) { Text("検索") }
            when (sessionState) {
                is SessionState.Expired -> {
                    Text("再ログインが必要です")
                    Button(onClick = onReLogin) { Text("ログインし直す") }
                }
                is SessionState.Suspect -> {
                    Text("ログイン状態を確認できていません")
                    OutlinedButton(onClick = onReLogin) { Text("ログインし直す") }
                }
                else -> Unit
            }
            OutlinedButton(onClick = { container.appScope.launch { container.logout() } }) { Text("ログアウト") }
            if (container.isDebuggable) {
                var checkLines by remember { mutableStateOf<List<String>?>(null) }
                val scope = rememberCoroutineScope()
                OutlinedButton(onClick = {
                    checkLines = listOf("確認中…")
                    scope.launch { checkLines = StreamCheck.run(container) }
                }) { Text("開発用: ストリーム確認") }
                checkLines?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                DebugPlaybackButtons(container)
            }
        }
    }
}

/** debug ビルドだけの再生確認。 */
@Composable
private fun DebugPlaybackButtons(container: AppContainer) {
    val scope = rememberCoroutineScope()
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { scope.launch { container.player.playSong(DebugPlayback.fixedSong) } }) { Text("開発用: 固定の曲を再生") }
        OutlinedButton(onClick = {
            scope.launch {
                val corrupted = container.streamCache.corruptForDebug(DebugPlayback.VIDEO_ID)
                Log.i(WaveApplication.LOG_TAG, "debug corrupt cached url done=$corrupted")
                // バッファの外へ飛んで、DataSource を開き直させる
                container.player.controller().run { seekTo(currentPosition + 120_000) }
            }
        }) { Text("開発用: URL を壊して 2 分進む") }
    }
}
