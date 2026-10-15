package dev.tubebeam.send

import dev.tubebeam.adb.AdbError
import dev.tubebeam.adb.AdbSession
import dev.tubebeam.adb.AdbTransport
import dev.tubebeam.adb.PortProbe
import dev.tubebeam.adb.ShellResult
import dev.tubebeam.data.Settings
import dev.tubebeam.data.TvDao
import dev.tubebeam.data.TvDevice
import dev.tubebeam.data.TvRepository
import dev.tubebeam.discovery.CandidateSource
import dev.tubebeam.discovery.TvFinder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendCoordinatorTest {
    /** Hosts in [alive] accept connections; each answers identity queries with its mapped id and `am start` with success. */
    private class FakeTransport(private val alive: Map<String, String>) : AdbTransport {
        val sentFrom = mutableListOf<String>()
        override suspend fun probe(host: String, port: Int, timeoutMs: Int) =
            if (host in alive) PortProbe.OPEN else PortProbe.UNREACHABLE
        override suspend fun connect(host: String, port: Int, connectTimeoutMs: Int, ioTimeoutMs: Int): AdbSession {
            val id = alive[host] ?: throw AdbError.Unreachable(host)
            return object : AdbSession {
                override suspend fun shell(command: String): ShellResult {
                    if (command.startsWith("getprop ro.serialno")) return ShellResult("$id\nandroid", "", 0)
                    if ("am start" in command) sentFrom += host
                    return ShellResult("Starting: Intent", "", 0)
                }
                override fun close() = Unit
            }
        }
        override suspend fun pair(host: String, pairingPort: Int, pairingCode: String) = Unit
    }

    private class MemoryDao(initial: TvDevice) : TvDao {
        val state = MutableStateFlow(listOf(initial))
        val tv get() = state.value[0]
        override fun observeAll(): Flow<List<TvDevice>> = state
        override suspend fun getAll() = state.value
        override fun observeById(id: Long): Flow<TvDevice?> = state.map { l -> l.firstOrNull { it.id == id } }
        override suspend fun getById(id: Long) = state.value.firstOrNull { it.id == id }
        override suspend fun count() = state.value.size
        override suspend fun insert(tv: TvDevice): Long { state.value = state.value + tv; return tv.id }
        override suspend fun update(tv: TvDevice) { state.value = state.value.map { if (it.id == tv.id) tv else it } }
        override suspend fun delete(tv: TvDevice) { state.value = state.value.filter { it.id != tv.id } }
        override suspend fun setDefault(id: Long) { state.value = state.value.map { it.copy(isDefault = it.id == id) } }
        override suspend fun clearDefault() { state.value = state.value.map { it.copy(isDefault = false) } }
        override suspend fun markSeen(id: Long, timestamp: Long) { state.value = state.value.map { if (it.id == id) it.copy(lastSeenOk = timestamp) else it } }
        override suspend fun updateHost(id: Long, host: String) { state.value = state.value.map { if (it.id == id) it.copy(host = host) else it } }
        override suspend fun updateSerial(id: Long, serial: String?) { state.value = state.value.map { if (it.id == id) it.copy(serial = serial) else it } }
    }

    private class ListSource(val hosts: List<String>) : CandidateSource {
        override suspend fun candidates(port: Int, timeoutMs: Long) = hosts
    }

    private val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    private val settings: suspend () -> Settings = { Settings() }

    private fun coordinator(t: FakeTransport, dao: MemoryDao, candidates: List<String>) =
        SendCoordinator(VideoSender(t), TvRepository(dao), settings, TvFinder(t, listOf(ListSource(candidates))))

    @Test
    fun relocatesWhenStoredAddressIsDeadAndSavesNewHost() = runTest {
        val t = FakeTransport(mapOf("192.168.1.130" to "TV1"))
        val dao = MemoryDao(TvDevice(id = 1, name = "TV", host = "192.168.1.121", targetPackage = "org.smarttube.stable", serial = "serial:TV1"))
        val progress = mutableListOf<SendProgress>()
        val r = coordinator(t, dao, listOf("192.168.1.130")).send(dao.tv, url) { progress += it }
        assertTrue(r is SendResult.Success)
        assertEquals(listOf("192.168.1.130"), t.sentFrom)
        assertEquals("192.168.1.130", dao.tv.host)
        assertEquals(listOf(SendProgress.Sending, SendProgress.Searching, SendProgress.Relocated("192.168.1.130")), progress)
    }

    @Test
    fun failsNormallyWhenTvIsNowhere() = runTest {
        val t = FakeTransport(emptyMap())
        val dao = MemoryDao(TvDevice(id = 1, name = "TV", host = "192.168.1.121", targetPackage = "x", serial = "serial:TV1"))
        val r = coordinator(t, dao, listOf("192.168.1.130")).send(dao.tv, url)
        assertTrue(r is SendResult.Failure && r.error is AdbError.Unreachable)
        assertEquals("192.168.1.121", dao.tv.host)
    }

    @Test
    fun doesNotSearchWithoutIdentity() = runTest {
        val t = FakeTransport(mapOf("192.168.1.130" to "TV1"))
        val dao = MemoryDao(TvDevice(id = 1, name = "TV", host = "192.168.1.121", targetPackage = "x", serial = null))
        val progress = mutableListOf<SendProgress>()
        val r = coordinator(t, dao, listOf("192.168.1.130")).send(dao.tv, url) { progress += it }
        assertTrue(r is SendResult.Failure)
        assertEquals(listOf(SendProgress.Sending), progress)
    }

    @Test
    fun learnsIdentityOnFirstSuccessfulSend() = runTest {
        val t = FakeTransport(mapOf("192.168.1.121" to "TV1"))
        val dao = MemoryDao(TvDevice(id = 1, name = "TV", host = "192.168.1.121", targetPackage = "x", serial = null))
        assertNull(dao.tv.serial)
        val r = coordinator(t, dao, emptyList()).send(dao.tv, url)
        assertTrue(r is SendResult.Success)
        assertEquals("serial:TV1", dao.tv.serial)
    }
}
