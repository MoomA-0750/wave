package xyz.mooma0750.wave.search

import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.session.FailureKind

@Composable
fun failureMessage(kind: FailureKind?): String =
    when (kind) {
        FailureKind.NotLoggedIn -> stringResource(R.string.search_failure_not_logged_in)
        FailureKind.Expired -> stringResource(R.string.search_failure_expired)
        FailureKind.Suspect -> stringResource(R.string.search_failure_suspect)
        FailureKind.Network -> stringResource(R.string.search_failure_network)
        FailureKind.Http -> stringResource(R.string.search_failure_http)
        FailureKind.Parse -> stringResource(R.string.search_failure_parse)
        FailureKind.AccountUnverified -> stringResource(R.string.search_failure_account_unverified)
        null -> ""
    }
