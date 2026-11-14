package seeyuer.sayin.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.ContextCompat

internal fun Context.localNetworkPermitted(): Boolean = Build.VERSION.SDK_INT < 37 ||
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

/** Observe only while a visible/recording owner needs the connection. */
internal class LanNetwork(context: Context, private val changed: () -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = changed()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
    }
    fun current(): Network? {
        fun lan(network: Network) = manager.getNetworkCapabilities(network)?.let {
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } == true
        return manager.activeNetwork?.takeIf(::lan) ?: manager.allNetworks.firstOrNull(::lan)
    }
    fun key(network: Network?): String = "$network/${network?.let { manager.getLinkProperties(it)?.linkAddresses }}"
    @Synchronized fun start() {
        if (!registered) {
            manager.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), callback)
            registered = true
        }
    }
    @Synchronized fun stop() { if (registered) { registered = false; manager.unregisterNetworkCallback(callback) } }
}
