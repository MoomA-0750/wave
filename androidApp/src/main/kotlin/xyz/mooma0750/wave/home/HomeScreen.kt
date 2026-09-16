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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.session.AccountResult
import xyz.mooma0750.wave.core.session.PremiumStatus
import xyz.mooma0750.wave.core.session.SessionState
import xyz.mooma0750.wave.login.LoginViewModel

@Composable
fun HomeScreen(
    container: AppContainer,
    onReLogin: () -> Unit,
) {
    val sessionState by container.session.state.collectAsStateWithLifecycle()
    var account by remember { mutableStateOf<AccountResult?>(null) }
    var premium by remember { mutableStateOf<PremiumStatus?>(null) }

    LaunchedEffect(Unit) {
        // AuthManager の LoggedIn は、WaveSession への反映より先に届くことがある
        container.session.state.first { it !is SessionState.LoggedOut }
        val fetchedAccount = withContext(Dispatchers.Default) { container.session.accountInfo() }
        account = fetchedAccount
        // 値は出さず、種類だけを記録する（実機確認用）
        Log.i(WaveApplication.LOG_TAG, "account result=${fetchedAccount::class.simpleName} name=${(fetchedAccount as? AccountResult.Ok)?.name != null}")
        val status = withContext(Dispatchers.Default) { container.session.premiumStatus() }
        premium = status
        Log.i(WaveApplication.LOG_TAG, "premium=$status session=${container.session.state.value}")
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                when (val current = account) {
                    null -> "アカウントを確認しています…"
                    is AccountResult.Ok -> current.name ?: "名前を取得できませんでした"
                    is AccountResult.Failed -> "アカウントを取得できませんでした"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Premium 音質: " +
                    when (premium) {
                        null -> "確認中…"
                        PremiumStatus.Available -> "利用可"
                        PremiumStatus.Unavailable -> "利用不可"
                        PremiumStatus.Unknown -> "未確認"
                    },
            )
            if (sessionState is SessionState.Expired) {
                Text("再ログインが必要です")
                Button(onClick = onReLogin) { Text("ログインし直す") }
            }
            OutlinedButton(onClick = {
                container.appScope.launch {
                    container.authManager.logout()
                    LoginViewModel.clearWebViewCredentials()
                }
            }) { Text("ログアウト") }
        }
    }
}
