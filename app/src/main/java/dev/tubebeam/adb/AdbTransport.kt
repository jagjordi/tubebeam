package dev.tubebeam.adb

/** Output of one shell command on the TV. */
data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int) {
    val allOutput: String get() = stdout + stderr
}

/** Result of a plain TCP probe, used before the (slow) ADB handshake. */
enum class PortProbe { OPEN, REFUSED, UNREACHABLE }

/** One open ADB connection to a TV. Always closed after use; we never keep sockets alive. */
interface AdbSession : AutoCloseable {
    /** Runs [command] in the TV's shell. Throws [AdbError] on transport failure. */
    suspend fun shell(command: String): ShellResult
}

/**
 * The only thing the app knows about the ADB library. Implementations must run all
 * network work on Dispatchers.IO and convert library exceptions to [AdbError].
 */
interface AdbTransport {
    /** Cheap TCP reachability check, no ADB traffic. */
    suspend fun probe(host: String, port: Int, timeoutMs: Int = 2_000): PortProbe

    /**
     * Opens a connection and completes the ADB auth handshake.
     * [ioTimeoutMs] bounds every socket read, so use a long value (60 s) while the user
     * is expected to approve the key on the TV and a short one (5 s) for routine sends.
     */
    suspend fun connect(
        host: String,
        port: Int,
        connectTimeoutMs: Int = 4_000,
        ioTimeoutMs: Int = 5_000,
    ): AdbSession

    /** Android 11+ wireless-debugging pairing (phase 4). */
    suspend fun pair(host: String, pairingPort: Int, pairingCode: String)
}
