package xyz.mooma0750.wave.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 今の通信。online = 外へ出られる通信がある、unmetered = 従量制でない（Wi-Fi など）。 */
data class NetState(
    val online: Boolean,
    val unmetered: Boolean,
)

/**
 * 端末の通信の変化を見張る（ダウンロードの待ちと、通信できないときに取っていない曲を飛ばすのに使う、ADR-0016 の 8・18）。
 * VPN（Tailscale など）は下の通信が無くても「つながっている」と見えるので、VPN でない通信だけを数える。
 */
class NetworkMonitor(
    context: Context,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networks = java.util.concurrent.ConcurrentHashMap<Network, NetworkCapabilities>()
    private val mutableState = MutableStateFlow(NetState(online = connectivity == null, unmetered = false))
    val state: StateFlow<NetState> = mutableState.asStateFlow()

    val online: Boolean get() = mutableState.value.online

    init {
        // 最初の値はすぐに出す（コールバックは少し後で届く）
        connectivity?.let { manager ->
            @Suppress("DEPRECATION")
            manager.allNetworks.forEach { network ->
                manager.getNetworkCapabilities(network)?.takeIf { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }?.let { networks[network] = it }
            }
            publish()
        }
        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
        connectivity?.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    networks[network] = capabilities
                    publish()
                }

                override fun onLost(network: Network) {
                    networks.remove(network)
                    publish()
                }
            },
        )
    }

    private fun publish() {
        val all = networks.values
        mutableState.value =
            NetState(
                online = all.isNotEmpty(),
                // Wi-Fi などがあれば、OS はそちらを使う
                unmetered = all.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
            )
    }
}
