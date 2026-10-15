package dev.tubebeam.discovery

/** Produces IPv4 addresses that might be an Android TV with ADB on [port]. Cheap sources first. */
interface CandidateSource {
    suspend fun candidates(port: Int, timeoutMs: Long): List<String>
}
