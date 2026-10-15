package dev.tubebeam.discovery

import dev.tubebeam.adb.AdbError
import dev.tubebeam.adb.AdbSession
import dev.tubebeam.adb.AdbTransport
import dev.tubebeam.adb.PortProbe
import dev.tubebeam.adb.ShellResult
import dev.tubebeam.data.TvDevice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvFinderTest {
    /** Maps host -> identity output; unknown hosts are unreachable. */
    private class FakeTransport(private val identities: Map<String, String>) : AdbTransport {
        val connected = mutableListOf<String>()
        override suspend fun probe(host: String, port: Int, timeoutMs: Int) =
            if (host in identities) PortProbe.OPEN else PortProbe.UNREACHABLE
        override suspend fun connect(host: String, port: Int, connectTimeoutMs: Int, ioTimeoutMs: Int): AdbSession {
            connected += host
            val id = identities[host] ?: throw AdbError.Unreachable(host)
            return object : AdbSession {
                override suspend fun shell(command: String) = ShellResult(id, "", 0)
                override fun close() = Unit
            }
        }
        override suspend fun pair(host: String, pairingPort: Int, pairingCode: String) = Unit
    }

    private class ListSource(val hosts: List<String>) : CandidateSource {
        var calls = 0
        override suspend fun candidates(port: Int, timeoutMs: Long): List<String> { calls++; return hosts }
    }

    private val tv = TvDevice(id = 1, name = "Living room", host = "192.168.1.121", serial = "serial:TV1")

    @Test
    fun findsTvAtNewAddressFromFirstSource() = runTest {
        val t = FakeTransport(mapOf("192.168.1.130" to "TV1\nabc", "192.168.1.131" to "OTHER\nxyz"))
        val mdns = ListSource(listOf("192.168.1.131", "192.168.1.130"))
        val sweep = ListSource(listOf("192.168.1.130"))
        val r = TvFinder(t, listOf(mdns, sweep)).find(tv)
        assertEquals(FindResult.Found("192.168.1.130"), r)
        assertEquals(0, sweep.calls) // never needed
    }

    @Test
    fun fallsBackToSecondSourceAndSkipsOldAddress() = runTest {
        val t = FakeTransport(mapOf("192.168.1.130" to "TV1\nabc"))
        val mdns = ListSource(emptyList())
        val sweep = ListSource(listOf("192.168.1.121", "192.168.1.130"))
        val r = TvFinder(t, listOf(mdns, sweep)).find(tv)
        assertEquals(FindResult.Found("192.168.1.130"), r)
        assertTrue("stored (failed) host must not be re-tried", "192.168.1.121" !in t.connected)
    }

    @Test
    fun notFoundWhenNoCandidateMatches() = runTest {
        val t = FakeTransport(mapOf("192.168.1.131" to "OTHER\nxyz"))
        val r = TvFinder(t, listOf(ListSource(listOf("192.168.1.131")))).find(tv)
        assertEquals(FindResult.NotFound, r)
    }

    @Test
    fun noIdentityWhenSerialUnknown() = runTest {
        val src = ListSource(listOf("192.168.1.130"))
        val r = TvFinder(FakeTransport(emptyMap()), listOf(src)).find(tv.copy(serial = null))
        assertEquals(FindResult.NoIdentity, r)
        assertEquals(0, src.calls)
    }

    @Test
    fun reportsPhases() = runTest {
        val t = FakeTransport(mapOf("192.168.1.130" to "TV1\nabc"))
        val phases = mutableListOf<FindPhase>()
        TvFinder(t, listOf(ListSource(emptyList()), ListSource(listOf("192.168.1.130")))).find(tv) { phases += it }
        assertEquals(listOf(FindPhase.MDNS, FindPhase.SWEEP, FindPhase.VERIFY), phases)
    }
}
