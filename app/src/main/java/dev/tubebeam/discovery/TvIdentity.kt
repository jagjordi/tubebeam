package dev.tubebeam.discovery

/**
 * A TV's stable identity, read over ADB. IPs change; the serial does not.
 * `ro.serialno` is preferred; some boxes report "unknown" there, so `android_id` is the fallback.
 */
object TvIdentity {
    /** One round trip: first line serial, second line android_id. */
    const val COMMAND = "getprop ro.serialno; settings get secure android_id"

    fun parse(output: String): String? {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val serial = lines.getOrNull(0)?.takeIf(::usable)
        val androidId = lines.getOrNull(1)?.takeIf(::usable)
        return when {
            serial != null -> "serial:$serial"
            androidId != null -> "android_id:$androidId"
            else -> null
        }
    }

    private fun usable(v: String): Boolean {
        val l = v.lowercase()
        return l.length >= 3 && l !in setOf("unknown", "null", "0000000000000000") && !l.startsWith("error") && l.any { it != '0' }
    }

    /** Short form for the UI, e.g. "serial:ABC123" -> "ABC123". */
    fun display(identity: String?): String? = identity?.substringAfter(':')
}
