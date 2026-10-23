package xyz.mooma0750.wave.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 今の通信。online = 外へ出られる通信がある、unmetered = 従量制でない（Wi-Fi など）。 */
data class NetState(
    val online: Boolean,
    val unmetered: Boolean,
)

/** 端末の通信の変化を見張る（ダウンロードの待ちと、通信できないときに取っていない曲を飛ばすのに使う、ADR-0016 の 8・18）。 */
class NetworkMonitor(
    context: Context,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutableState = MutableStateFlow(current())
    val state: StateFlow<NetState> = mutableState.asStateFlow()

    val online: Boolean get() = mutableState.value.online

    init {
        connectivity?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    mutableState.value = stateOf(capabilities)
                }

                override fun onLost(network: Network) {
                    mutableState.value = current()
                }

                override fun onAvailable(network: Network) {
                    mutableState.value = current()
                }
            },
        )
    }

    private fun current(): NetState {
        val manager = connectivity ?: return NetState(online = true, unmetered = false)
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return NetState(online = false, unmetered = false)
        return stateOf(capabilities)
    }

    private fun stateOf(capabilities: NetworkCapabilities) =
        NetState(
            online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            // 一時的に従量制でない扱いの通信（NOT_METERED が後から付く）も、OS の判断に従う
            unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
}
