package dev.tubebeam.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetAppTest {
    @Test
    fun prefersBetaOverStableOverYouTube() {
        val out = """
            package:com.google.android.youtube.tv
            package:com.teamsmart.videomanager.tv
            package:com.liskovsoft.smarttubetv.beta
            package:com.android.settings
        """.trimIndent()
        assertEquals(TargetApp.SMARTTUBE_BETA, TargetApp.pickFrom(out))
    }

    @Test
    fun stableSmartTube() =
        assertEquals(TargetApp.SMARTTUBE_STABLE, TargetApp.pickFrom("package:org.smarttube.stable\npackage:x.y"))

    @Test
    fun fallsBackToYouTube() {
        assertEquals(TargetApp.YOUTUBE_TV, TargetApp.pickFrom("package:com.google.android.youtube.tv\npackage:x.y"))
    }

    @Test
    fun unknownForkMatchedByPattern() =
        assertEquals("org.example.smarttube.custom", TargetApp.pickFrom("package:org.example.smarttube.custom\npackage:x.y"))

    @Test
    fun exactMatchBeatsPattern() {
        val out = "package:org.aaa.smarttube.fork\npackage:com.google.android.youtube.tv"
        assertEquals(TargetApp.YOUTUBE_TV, TargetApp.pickFrom(out))
        assertEquals(listOf(TargetApp.YOUTUBE_TV, "org.aaa.smarttube.fork"), TargetApp.candidatesFrom(out))
    }

    @Test
    fun noneInstalled() = assertNull(TargetApp.pickFrom("package:com.android.settings\n"))
}
