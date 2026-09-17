package xyz.mooma0750.wave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.home.HomeScreen
import xyz.mooma0750.wave.login.LoginScreen
import xyz.mooma0750.wave.login.LoginViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WaveApplication).container
        setContent {
            MaterialTheme {
                val authState by container.authManager.state.collectAsStateWithLifecycle()
                // ログインが完了するたびに増える。ログイン画面の ViewModel をログインごとに作り直す（完了済みの状態を持ち越さない）
                val loginGeneration by container.loginGeneration.collectAsStateWithLifecycle()
                // 失効したときに、ログイン済みのまま再ログイン画面を出す。プロセスが復元されたら出さない（保存しない）
                var reLogin by remember { mutableStateOf(false) }

                when (val state = authState) {
                    AuthState.Restoring -> Progress()
                    AuthState.LoggingOut -> Progress("ログアウトしています…")
                    is AuthState.LoggedIn -> {
                        LaunchedEffect(loginGeneration) { reLogin = false }
                        if (reLogin) {
                            BackHandler { reLogin = false }
                            LoginScreen(
                                viewModel(key = "relogin-$loginGeneration") { LoginViewModel(container) },
                                onCancel = { reLogin = false },
                            )
                        } else {
                            HomeScreen(container, obtainedAtEpochMs = state.obtainedAtEpochMs, onReLogin = { reLogin = true })
                        }
                    }
                    AuthState.LoggedOut, is AuthState.Unreadable ->
                        LoginScreen(
                            viewModel(key = "login-$loginGeneration") { LoginViewModel(container) },
                            savedLoginUnreadable = state is AuthState.Unreadable,
                        )
                }
            }
        }
    }
}

@Composable
private fun Progress(message: String? = null) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        if (message != null) Text(message)
    }
}
