package seeyuer.sayin.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Untrusted routing hints only. Never contains credentials or changes trust. */
internal data class HostCandidate(val address: String, val port: Int)

internal fun interface HostDiscovery {
    suspend fun discover(hostId: String, network: Network?): List<HostCandidate>
}

/** One bounded foreground round: 2s browse, at most 8 services / 4 IPv4 routes,
 * 2s per resolution, 12s overall (caller). Every callback belongs to this round.
 */
internal class NsdHostDiscovery(private val context: Context) : HostDiscovery {
    private val legacyInFlight = AtomicBoolean(false)
    @Suppress("DEPRECATION")
    override suspend fun discover(hostId: String, network: Network?): List<HostCandidate> {
        if (network == null) return emptyList()
        val manager = context.getSystemService(NsdManager::class.java)
        val active = AtomicBoolean(true)
        val services = Channel<NsdServiceInfo>(8)
        val seen = mutableSetOf<String>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { if (!active.get()) runCatching { manager.stopServiceDiscovery(this) } }
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { services.close() }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!active.get() || !discoveryInstanceMatches(info.serviceName, hostId) || info.serviceType.trimEnd('.') != SERVICE_TYPE) return
                synchronized(seen) {
                    if (seen.size < 8 && seen.add(info.serviceName)) services.trySend(info)
                }
            }
        }
        // Older Android needs this only while browsing/resolving. No idle lock.
        val lock = if (Build.VERSION.SDK_INT < 34) context.getSystemService(WifiManager::class.java)
            .createMulticastLock("sayin-discovery").apply { setReferenceCounted(false); acquire() } else null
        try {
            if (Build.VERSION.SDK_INT >= 33) manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, network, { it.run() }, listener)
            else manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            delay(2000)
            val result = linkedSetOf<HostCandidate>()
            repeat(8) {
                val info = services.tryReceive().getOrNull() ?: return@repeat
                if (Build.VERSION.SDK_INT >= 33 && network != null) info.network = network
                val resolved = withTimeoutOrNull(2000) { resolve(manager, info) }
                // On legacy Android resolution cannot be cancelled. Stop this round
                // at the first timeout, so only one OS-owned operation can be late.
                if (resolved == null && Build.VERSION.SDK_INT < 34) return result.toList()
                if (resolved != null && resolved.attributes["host_id"]?.toString(Charsets.UTF_8) == hostId && resolved.port in 1..65535 && resolved.attributes["v"]?.toString(Charsets.UTF_8) == "2") {
                    val addresses = if (Build.VERSION.SDK_INT >= 34) resolved.hostAddresses else listOfNotNull(resolved.host)
                    for (address in addresses) {
                        if (address is Inet4Address && !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress && !address.isLinkLocalAddress) {
                            val onLink = context.getSystemService(ConnectivityManager::class.java).getLinkProperties(network)
                                ?.linkAddresses?.any { prefix ->
                                    prefix.address is Inet4Address && sameIpv4Subnet(prefix.address.address, address.address, prefix.prefixLength)
                                } == true
                            if (onLink && resolved.attributes["ipv4"]?.toString(Charsets.UTF_8) == address.hostAddress && result.size < 4) result.add(HostCandidate(address.hostAddress!!, resolved.port))
                        }
                    }
                }
                if (result.size >= 4) return result.toList()
            }
            return result.toList()
        } finally {
            active.set(false)
            services.close()
            runCatching { manager.stopServiceDiscovery(listener) }
            lock?.release()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo): NsdServiceInfo? {
        var modern: NsdManager.ServiceInfoCallback? = null
        var legacy: NsdManager.ResolveListener? = null
        try {
            return suspendCancellableCoroutine { continuation ->
                if (Build.VERSION.SDK_INT >= 34) {
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceUpdated(info: NsdServiceInfo) { complete(continuation, info) }
                        override fun onServiceLost() { complete(continuation, null) }
                        override fun onServiceInfoCallbackRegistrationFailed(error: Int) { complete(continuation, null) }
                        override fun onServiceInfoCallbackUnregistered() = Unit
                    }
                    modern = callback
                    manager.registerServiceInfoCallback(info, { task -> task.run() }, callback)
                } else {
                    if (!legacyInFlight.compareAndSet(false, true)) { continuation.resume(null); return@suspendCancellableCoroutine }
                    val released = AtomicBoolean(false)
                    fun releaseSlot() { if (released.compareAndSet(false, true)) legacyInFlight.set(false) }
                    val listener = object : NsdManager.ResolveListener {
                        override fun onServiceResolved(info: NsdServiceInfo) { releaseSlot(); complete(continuation, info) }
                        override fun onResolveFailed(info: NsdServiceInfo, error: Int) { releaseSlot(); complete(continuation, null) }
                        override fun onResolutionStopped(info: NsdServiceInfo) { releaseSlot(); complete(continuation, null) }
                    }
                    legacy = listener
                    try { manager.resolveService(info, listener) } catch (failure: Exception) { releaseSlot(); throw failure }
                }
            }
        } finally {
            if (Build.VERSION.SDK_INT >= 34) modern?.let { runCatching { manager.unregisterServiceInfoCallback(it) } }
            if (Build.VERSION.SDK_INT >= 33 && android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7) {
                legacy?.let { runCatching { manager.stopServiceResolution(it) } }
            }
        }
    }

    private fun complete(continuation: CancellableContinuation<NsdServiceInfo?>, info: NsdServiceInfo?) {
        // NSD may deliver several updates on arbitrary binder threads.
        synchronized(continuation) { if (continuation.isActive) continuation.resume(info) }
    }

    companion object { const val SERVICE_TYPE = "_voicelink._tcp" }
}

/** Pure on-link IPv4 filter; never use unauthenticated DNS to connect off-link. */
internal fun sameIpv4Subnet(local: ByteArray, candidate: ByteArray, prefix: Int): Boolean {
    if (local.size != 4 || candidate.size != 4 || prefix !in 1..32) return false
    return (0 until prefix).all { bit ->
        val mask = 1 shl (7 - bit % 8)
        local[bit / 8].toInt() and mask == candidate[bit / 8].toInt() and mask
    }
}

/** A public name is only a cheap filter against unrelated services, not trust.
 * Windows may suffix an instance after a name collision.
 */
internal fun discoveryInstanceMatches(name: String, hostId: String): Boolean {
    val expected = "SayIn-$hostId"
    return name.equals(expected, ignoreCase = true) || name.startsWith("$expected (", ignoreCase = true)
}
