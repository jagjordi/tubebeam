package dev.tubebeam.adb

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.exception.AdbAuthException
import com.flyfishxu.kadb.exception.AdbPairAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** [AdbTransport] backed by Kadb. Key material comes from [AdbKeyStore] via Kadb's global KadbCert. */
class KadbTransport(private val keyStore: AdbKeyStore) : AdbTransport {

    override suspend fun probe(host: String, port: Int, timeoutMs: Int): PortProbe = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            PortProbe.OPEN
        } catch (e: ConnectException) {
            if (e.isRefused()) PortProbe.REFUSED else PortProbe.UNREACHABLE
        } catch (e: IOException) {
            PortProbe.UNREACHABLE
        }
    }

    override suspend fun connect(host: String, port: Int, connectTimeoutMs: Int, ioTimeoutMs: Int): AdbSession =
        withContext(Dispatchers.IO) {
            keyStore.ensureReady()
            val kadb = Kadb.create(host, port, connectTimeout = connectTimeoutMs, socketTimeout = ioTimeoutMs)
            try {
                // Kadb connects lazily; force the handshake here so auth errors surface at connect time.
                kadb.supportsFeature("shell_v2")
            } catch (t: Throwable) {
                runCatching { kadb.close() }
                throw mapConnectError(t, host, port)
            }
            // The blocking handshake can't be interrupted; if the caller gave up meanwhile, don't leak the socket.
            if (!currentCoroutineContext().isActive) {
                runCatching { kadb.close() }
                throw CancellationException("connect cancelled")
            }
            KadbSession(kadb, host)
        }

    override suspend fun pair(host: String, pairingPort: Int, pairingCode: String) = withContext(Dispatchers.IO) {
        keyStore.ensureReady()
        try {
            Kadb.pair(host, pairingPort, pairingCode)
        } catch (t: Throwable) {
            throw mapConnectError(t, host, pairingPort)
        }
    }

    private class KadbSession(private val kadb: Kadb, private val host: String) : AdbSession {
        override suspend fun shell(command: String): ShellResult = withContext(Dispatchers.IO) {
            try {
                val r = kadb.shell(command)
                ShellResult(r.output, r.errorOutput, r.exitCode)
            } catch (t: Throwable) {
                throw when (t) {
                    is AdbError -> t
                    is SocketTimeoutException -> AdbError.Timeout(host, t)
                    is EOFException -> AdbError.Unreachable(host, t)
                    else -> AdbError.Unknown(t)
                }
            }
        }

        override fun close() {
            runCatching { kadb.close() }
        }
    }

    private fun mapConnectError(t: Throwable, host: String, port: Int): AdbError = when (t) {
        is AdbError -> t
        is AdbAuthException, is AdbPairAuthException -> AdbError.Unauthorized(host, t)
        is ConnectException -> if (t.isRefused()) AdbError.Refused(host, port, t) else AdbError.Unreachable(host, t)
        is NoRouteToHostException, is UnknownHostException -> AdbError.Unreachable(host, t)
        is SocketTimeoutException ->
            // Socket.connect() and socket reads both throw this; only the latter means the TV is up.
            if (t.message?.contains("connect", ignoreCase = true) == true) AdbError.Unreachable(host, t)
            else AdbError.Timeout(host, t)
        // adbd drops the connection without a word when the user denies the prompt or the key is revoked.
        is EOFException -> AdbError.Unauthorized(host, t)
        is IOException -> when {
            t.message?.contains("ECONNREFUSED", ignoreCase = true) == true -> AdbError.Refused(host, port, t)
            t.message?.contains("closed", ignoreCase = true) == true -> AdbError.Unauthorized(host, t)
            t.message?.contains("unreachable", ignoreCase = true) == true -> AdbError.Unreachable(host, t)
            else -> AdbError.Unknown(t)
        }
        else -> AdbError.Unknown(t)
    }

    private fun ConnectException.isRefused(): Boolean =
        message?.contains("ECONNREFUSED", ignoreCase = true) == true ||
            message?.contains("refused", ignoreCase = true) == true
}
