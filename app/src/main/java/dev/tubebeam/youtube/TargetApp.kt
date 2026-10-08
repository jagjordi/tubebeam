package dev.tubebeam.youtube

/** Which app on the TV should receive the VIEW intent. */
object TargetApp {
    const val SMARTTUBE_BETA = "com.liskovsoft.smarttubetv.beta"
    const val SMARTTUBE_STABLE = "org.smarttube.stable"
    const val SMARTTUBE_STABLE_OLD = "com.teamsmart.videomanager.tv"
    const val SMARTTUBE_LEGACY = "com.liskovsoft.smarttubetv"
    const val YOUTUBE_TV = "com.google.android.youtube.tv"

    /** Most preferred first. */
    val PREFERRED = listOf(SMARTTUBE_BETA, SMARTTUBE_STABLE, SMARTTUBE_STABLE_OLD, SMARTTUBE_LEGACY, YOUTUBE_TV)

    /** Anything that smells like a SmartTube fork, used when no exact name matches. */
    private val fallbackPattern = Regex("smarttube|liskovsoft|teamsmart|videomanager", RegexOption.IGNORE_CASE)

    /** Shell command whose output [candidatesFrom] and [pickFrom] understand. */
    const val DETECT_COMMAND = "pm list packages"

    /** All installed packages that could be a player, preferred ones first. */
    fun candidatesFrom(pmListOutput: String): List<String> {
        val installed = pmListOutput.lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() }
            .toList()
        val exact = PREFERRED.filter { it in installed }
        val fuzzy = installed.filter { it !in exact && fallbackPattern.containsMatchIn(it) }.sorted()
        return exact + fuzzy
    }

    /** Parses `pm list packages` output and returns the best installed candidate, or null. */
    fun pickFrom(pmListOutput: String): String? = candidatesFrom(pmListOutput).firstOrNull()

    fun displayName(packageName: String?): String = when (packageName) {
        SMARTTUBE_BETA -> "SmartTube (beta)"
        SMARTTUBE_STABLE, SMARTTUBE_STABLE_OLD, SMARTTUBE_LEGACY -> "SmartTube"
        YOUTUBE_TV -> "YouTube"
        null -> "auto-detect"
        else -> packageName
    }
}
