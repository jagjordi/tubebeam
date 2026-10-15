package dev.tubebeam.discovery

import android.util.Log
import dev.tubebeam.adb.AdbTransport
import dev.tubebeam.data.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface FindResult {
    /** The TV answered at a new address (already different from the stored one). */
    data class Found(val host: String) : FindResult
    data object NotFound : FindResult
    /** We never recorded this TV's identity, so nothing on the network can be matched against it. */
    data object NoIdentity : FindResult
}

enum class FindPhase { MDNS, SWEEP, VERIFY }

/**
 * Locates a saved TV after its IP changed: collect candidate addresses (mDNS first, subnet sweep
 * if needed), then ADB-connect to each and compare [TvIdentity] with the one stored on the TV record.
 *
 * Connecting to a foreign Android TV that has debugging on would show its trust prompt; mDNS-first
 * ordering keeps that rare, and such connections time out quickly.
 */
class TvFinder(
    private val adb: AdbTransport,
    private val sources: List<CandidateSource>,
) {
    suspend fun find(tv: TvDevice, onPhase: (FindPhase) -> Unit = {}): FindResult = withContext(Dispatchers.IO) {
        val wanted = tv.serial ?: return@withContext FindResult.NoIdentity
        val tried = mutableSetOf(tv.host)
        for ((index, source) in sources.withIndex()) {
            onPhase(if (index == 0) FindPhase.MDNS else FindPhase.SWEEP)
            val candidates = runCatching { source.candidates(tv.port, SOURCE_TIMEOUT_MS) }
                .onFailure { Log.w(TAG, "candidate source failed: $it") }
                .getOrDefault(emptyList())
                .filter(tried::add)
            if (candidates.isEmpty()) continue
            onPhase(FindPhase.VERIFY)
            val host = firstMatching(candidates, tv.port, wanted)
            if (host != null) return@withContext FindResult.Found(host)
        }
        FindResult.NotFound
    }

    /** Reads the identity of every candidate (a few at a time) and returns the first that matches. */
    private suspend fun firstMatching(hosts: List<String>, port: Int, wanted: String): String? = coroutineScope {
        val gate = Semaphore(VERIFY_CONCURRENCY)
        hosts.map { host -> async { gate.withPermit { host.takeIf { identityOf(host, port) == wanted } } } }
            .firstNotNullOfOrNull { it.await() }
    }

    suspend fun identityOf(host: String, port: Int): String? = runCatching {
        adb.connect(host, port, connectTimeoutMs = CONNECT_TIMEOUT_MS, ioTimeoutMs = IO_TIMEOUT_MS).use { s ->
            TvIdentity.parse(s.shell(TvIdentity.COMMAND).stdout)
        }
    }.getOrNull()

    companion object {
        private const val TAG = "TvFinder"
        const val SOURCE_TIMEOUT_MS = 2_500L
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val IO_TIMEOUT_MS = 3_000
        private const val VERIFY_CONCURRENCY = 4
    }
}
