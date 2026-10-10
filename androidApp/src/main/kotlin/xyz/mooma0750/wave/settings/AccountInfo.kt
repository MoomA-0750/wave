package xyz.mooma0750.wave.settings

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.session.AccountResult
import xyz.mooma0750.wave.core.session.PremiumStatus
import xyz.mooma0750.wave.core.session.SessionState

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
