package dev.tubebeam.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import dev.tubebeam.adb.AdbTransport
import dev.tubebeam.adb.PortProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/**
 * Fallback when mDNS finds nothing: TCP-connects to [port] on every address of the phone's
 * IPv4 subnet (capped at 256 hosts around the phone's own address). ~2-3 s on a /24.
 */
class SubnetSweep(context: Context, private val adb: AdbTransport) : CandidateSource {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override suspend fun candidates(port: Int, timeoutMs: Long): List<String> = withContext(Dispatchers.IO) {
        val link = currentIpv4() ?: return@withContext emptyList()
        val hosts = hostsAround(link.address.address, link.prefixLength)
        val perHost = (timeoutMs / 4).coerceIn(300, 1_000).toInt()
        val gate = Semaphore(CONCURRENCY)
        coroutineScope {
            hosts.map { host ->
                async { gate.withPermit { host.takeIf { adb.probe(it, port, perHost) == PortProbe.OPEN } } }
            }.awaitAll().filterNotNull()
        }
    }

    private fun currentIpv4(): LinkAddress? {
        val network = cm.activeNetwork ?: return null
        return cm.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
    }

    companion object {
        private const val CONCURRENCY = 48

        /** All usable hosts of the enclosing /[prefix] network, shrunk to the /24 containing [self] when larger. */
        internal fun hostsAround(self: ByteArray, prefix: Int): List<String> {
            val p = prefix.coerceIn(24, 30)
            val ip = self.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
            val mask = (0xffffffffL shl (32 - p)) and 0xffffffffL
            val base = ip and mask
            val size = 1L shl (32 - p)
            return (1 until size - 1)
                .map { base + it }
                .filter { it != ip }
                .map { v -> "${v shr 24 and 0xff}.${v shr 16 and 0xff}.${v shr 8 and 0xff}.${v and 0xff}" }
        }
    }
}
