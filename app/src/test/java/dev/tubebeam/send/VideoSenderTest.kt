package dev.tubebeam.send

import dev.tubebeam.adb.AdbError
import dev.tubebeam.adb.AdbSession
import dev.tubebeam.adb.AdbTransport
import dev.tubebeam.adb.PortProbe
import dev.tubebeam.adb.ShellResult
import dev.tubebeam.youtube.TargetApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSenderTest {
    private val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=43"

    /** Scripted transport: answers each shell command from [responses] and records everything. */
    private class FakeTransport(
        private val responses: (String) -> ShellResult,
        private val connectError: AdbError? = null,
    ) : AdbTransport {
        val commands = mutableListOf<String>()
        var closed = false
        override suspend fun probe(host: String, port: Int, timeoutMs: Int) = PortProbe.OPEN
        override suspend fun connect(host: String, port: Int, connectTimeoutMs: Int, ioTimeoutMs: Int): AdbSession {
            connectError?.let { throw it }
            return object : AdbSession {
                override suspend fun shell(command: String): ShellResult {
                    commands += command
                    return responses(command)
                }
                override fun close() { closed = true }
            }
        }
        override suspend fun pair(host: String, pairingPort: Int, pairingCode: String) = Unit
    }

    private fun ok(out: String = "") = ShellResult(out, "", 0)

    @Test
    fun usesStoredPackageWakesAndQuotesUrl() = runTest {
        val t = FakeTransport({ ok("Starting: Intent { act=android.intent.action.VIEW }") })
        val r = VideoSender(t).send(SendTarget("1.2.3.4", 5555, TargetApp.SMARTTUBE_STABLE), url)
        assertTrue(r is SendResult.Success)
        assertEquals(TargetApp.SMARTTUBE_STABLE, (r as SendResult.Success).packageUsed)
        assertEquals(1, t.commands.size)
        assertEquals(
            "input keyevent KEYCODE_WAKEUP; am start -a android.intent.action.VIEW -d '$url' -p ${TargetApp.SMARTTUBE_STABLE}",
            t.commands[0],
        )
        assertTrue(t.closed)
    }

    @Test
    fun detectsPackageWhenNoneStored() = runTest {
        val t = FakeTransport({ cmd ->
            if (cmd == TargetApp.DETECT_COMMAND) ok("package:a\npackage:${TargetApp.SMARTTUBE_BETA}\n") else ok("Starting")
        })
        val r = VideoSender(t).send(SendTarget("1.2.3.4", 5555, null), url, wakeTv = false)
        assertEquals(TargetApp.SMARTTUBE_BETA, (r as SendResult.Success).packageUsed)
        assertEquals(TargetApp.DETECT_COMMAND, t.commands[0])
        assertTrue(t.commands[1].endsWith("-p ${TargetApp.SMARTTUBE_BETA}"))
        assertTrue(!t.commands[1].startsWith("input"))
    }

    @Test
    fun reportsMissingPlayerInsteadOfFiringIntoStub() = runTest {
        val t = FakeTransport({ cmd ->
            if (cmd == TargetApp.DETECT_COMMAND) ok("package:com.android.settings\n") else ok("Starting")
        })
        val r = VideoSender(t).send(SendTarget("1.2.3.4", 5555, null), url)
        assertTrue(r is SendResult.NoPlayerApp)
        assertEquals(listOf(TargetApp.DETECT_COMMAND), t.commands)
        assertTrue(t.closed)
    }

    @Test
    fun retriesWithoutPackageWhenAmStartFails() = runTest {
        val t = FakeTransport({ cmd ->
            if ("-p " in cmd) ok("Error: Activity not started, unable to resolve Intent") else ok("Starting")
        })
        val r = VideoSender(t).send(SendTarget("1.2.3.4", 5555, "com.gone.app"), url)
        assertTrue(r is SendResult.Success)
        assertEquals(null, (r as SendResult.Success).packageUsed)
        assertEquals(2, t.commands.size)
        assertTrue("-p" !in t.commands[1])
    }

    @Test
    fun reportsFailureWhenBothAttemptsFail() = runTest {
        val t = FakeTransport({ ok("Error: unable to resolve Intent") })
        val r = VideoSender(t).send(SendTarget("1.2.3.4", 5555, "x"), url)
        assertTrue(r is SendResult.Failure)
    }

    @Test
    fun connectErrorsAreReturnedTyped() = runTest {
        val t = FakeTransport({ ok() }, connectError = AdbError.Unauthorized("1.2.3.4"))
        val r = VideoSender(t).send(SendTarget("1.2.3.4", 5555, "x"), url)
        assertTrue((r as SendResult.Failure).error is AdbError.Unauthorized)
    }

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'a'\\''b'", VideoSender.shellQuote("a'b"))
    }
}
