package dev.tubebeam.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Finds Android TVs through the services they advertise on the LAN, with no scanning at all:
 * adbd itself (Android 11+), the Android TV remote protocol, and Cast. Takes about a second.
 */
class MdnsCandidates(context: Context) : CandidateSource {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    /** NsdManager allows one resolve at a time; a second one fails with FAILURE_ALREADY_ACTIVE. */
    private val resolveLock = Mutex()

    override suspend fun candidates(port: Int, timeoutMs: Long): List<String> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap.newKeySet<String>()
        val ordered = CopyOnWriteArrayList<String>()
        val resolves = CoroutineScope(coroutineContext + SupervisorJob())
        val browsers = SERVICE_TYPES.map { type ->
            Browser(type, resolves, timeoutMs) { host -> if (found.add(host)) ordered += host }
        }
        browsers.forEach { b ->
            try {
                nsd.discoverServices(b.type, NsdManager.PROTOCOL_DNS_SD, b)
                b.started = true
            } catch (e: Exception) {
                Log.w(TAG, "discoverServices(${b.type}) failed: $e")
            }
        }
        try {
            delay(timeoutMs)
        } finally {
            browsers.filter { it.started }.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            resolves.cancel()
        }
        ordered.toList()
    }

    private inner class Browser(
        val type: String,
        private val scope: CoroutineScope,
        private val timeoutMs: Long,
        private val onHost: (String) -> Unit,
    ) : NsdManager.DiscoveryListener {
        @Volatile var started = false
        private val seen = ConcurrentHashMap.newKeySet<String>()

        override fun onServiceFound(info: NsdServiceInfo) {
            if (!seen.add(info.serviceName)) return
            // Resolve off the callback thread; resolves are serialized by resolveLock.
            scope.launch {
                val host = withTimeoutOrNull(timeoutMs) { resolve(info) }
                if (host != null) onHost(host)
            }
        }

        private suspend fun resolve(info: NsdServiceInfo): String? = resolveLock.withLock {
            val result = CompletableDeferred<String?>()
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { result.complete(null) }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    result.complete((serviceInfo.host as? Inet4Address)?.hostAddress)
                }
            })
            // Bound the wait so a resolve that never calls back can't hold the lock forever.
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { result.await() }
        }

        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "start discovery failed for $type: $errorCode")
            runCatching { nsd.stopServiceDiscovery(this) }
            started = false
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    companion object {
        private const val TAG = "MdnsCandidates"
        private const val RESOLVE_TIMEOUT_MS = 1_500L
        val SERVICE_TYPES = listOf(
            "_adb-tls-connect._tcp.",   // adbd, wireless debugging (Android 11+)
            "_adb._tcp.",               // adbd, legacy TCP mode when mDNS is enabled
            "_androidtvremote2._tcp.",  // Android TV remote service
            "_googlecast._tcp.",        // Cast receiver, present on nearly every Android TV
        )
    }
}
