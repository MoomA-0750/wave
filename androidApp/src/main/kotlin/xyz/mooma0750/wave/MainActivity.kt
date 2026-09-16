package xyz.mooma0750.wave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.home.HomeScreen
import xyz.mooma0750.wave.login.LoginScreen
import xyz.mooma0750.wave.login.LoginUiState
import xyz.mooma0750.wave.login.LoginViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WaveApplication).container
        setContent {
            MaterialTheme {
                val authState by container.authManager.state.collectAsStateWithLifecycle()
                // 失効したときに、ログイン済みのまま再ログイン画面を出すためのフラグ（値は真偽値だけ）
                var reLogin by rememberSaveable { mutableStateOf(false) }

                when (authState) {
                    AuthState.Restoring ->
                        Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                    is AuthState.LoggedIn ->
                        if (reLogin) {
                            val loginViewModel = viewModel(key = "relogin") { LoginViewModel(container) }
                            LaunchedEffect(Unit) { loginViewModel.resetIfDone() }
                            val loginState by loginViewModel.uiState.collectAsStateWithLifecycle()
                            LoginScreen(loginViewModel)
                            LaunchedEffect(loginState) { if (loginState == LoginUiState.Done) reLogin = false }
                        } else {
                            HomeScreen(container, obtainedAtEpochMs = (authState as AuthState.LoggedIn).obtainedAtEpochMs, onReLogin = { reLogin = true })
                        }
                    AuthState.LoggedOut, is AuthState.Unreadable -> {
                        val loginViewModel = viewModel(key = "login") { LoginViewModel(container) }
                        val loginState by loginViewModel.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(Unit) { reLogin = false }
                        // ログアウト後に入り直したとき、前回のログインが Done のまま残っていたら（後始末中にログアウトした場合を含む）やり直せる状態に戻す
                        LaunchedEffect(loginState) { if (loginState == LoginUiState.Done) loginViewModel.resetIfDone() }
                        LoginScreen(loginViewModel)
                    }
                }
            }
        }
    }
}
