package dev.tubebeam.adb

/**
 * Typed failures for everything that talks to a TV. UI layers map these to friendly copy;
 * nothing below the UI should ever show a raw exception message to the user.
 */
sealed class AdbError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No TCP path to host:port (host down, wrong Wi-Fi, no route). */
    class Unreachable(host: String, cause: Throwable? = null) :
        AdbError("Can't reach $host", cause)

    /** TCP connection actively refused: nothing is listening (network debugging off). */
    class Refused(host: String, port: Int, cause: Throwable? = null) :
        AdbError("$host refused connection on port $port", cause)

    /** The TV closed the connection during auth: the key was rejected or never approved. */
    class Unauthorized(host: String, cause: Throwable? = null) :
        AdbError("$host has not trusted this phone", cause)

    /** Connected but no answer in time (typically: the approval prompt was never answered). */
    class Timeout(host: String, cause: Throwable? = null) :
        AdbError("Timed out waiting for $host", cause)

    class Unknown(cause: Throwable) :
        AdbError(cause.message ?: cause::class.java.simpleName, cause)
}
