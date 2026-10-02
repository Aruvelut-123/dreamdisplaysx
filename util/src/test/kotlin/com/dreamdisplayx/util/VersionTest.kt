package com.dreamdisplayx.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun parsesFourPartStableVersion() {
        val v = Version.parse("1.10.0.3")
        assertEquals(Version(1, 10, 0, 3, null), v)
        assertTrue(v!!.isStable)
        assertFalse(v.isPreRelease)
        assertEquals("1.10.0.3", v.toString())
    }

    @Test
    fun parsesVersionWithVowelPrefix() {
        val v = Version.parse("v1.10.0.3")
        assertEquals(Version(1, 10, 0, 3, null), v)
    }

    @Test
    fun parsesPrereleaseSuffixAndKeepsIt() {
        val v = Version.parse("1.10.0.4-dev")
        assertEquals(Version(1, 10, 0, 4, "dev"), v)
        assertFalse(v!!.isStable)
        assertTrue(v.isPreRelease)
        assertEquals("1.10.0.4-dev", v.toString())
    }

    @Test
    fun parsesDottedPrerelease() {
        val v = Version.parse("1.9.5.1-preview.5")
        assertEquals(Version(1, 9, 5, 1, "preview.5"), v)
    }

    @Test
    fun semverCoerceBugIsNotReproduced() {
        // semver4j's coerce silently drops the 4th part; ours must keep it.
        val v = Version.parse("1.10.0.3")
        assertEquals(4, v!!.toString().split('.').size)
        assertEquals("1.10.0.3", v.toString())
    }

    @Test
    fun ordersByBuildWithStableBeforePrerelease() {
        val stableOlder = Version.parse("1.10.0.2")!!
        val stableNewer = Version.parse("1.10.0.3")!!
        val prereleaseOfNewer = Version.parse("1.10.0.4-dev")!!

        assertTrue(stableOlder < stableNewer)
        assertTrue(stableNewer < prereleaseOfNewer)
        // Stable 1.10.0.4 would beat 1.10.0.4-dev if it existed.
        assertTrue(Version.parse("1.10.0.4")!! > prereleaseOfNewer)
    }

    @Test
    fun ordersMajorMinorPatch() {
        assertTrue(Version.parse("1.9.9.9")!! < Version.parse("1.10.0.0")!!)
        assertTrue(Version.parse("1.10.0.3")!! > Version.parse("1.10.0.1")!!)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Version.parse(""))
        assertNull(Version.parse("abc"))
        assertNull(Version.parse("1.2.3.4.5"))
        assertNull(Version.parse("1.2.3-"))
        assertNull(Version.parse("1.2.3-!@#"))
        assertNull(Version.parse("1..2"))
        assertNull(Version.parse("1.2.3+build"))
    }

    @Test
    fun compareVersionsFallsBackToLexicalOnGarbage() {
        assertTrue(compareVersions("1.10.0.3", "1.10.0.4-dev") < 0)
        assertEquals(0, compareVersions("not-a-version", "not-a-version"))
        // One side unparseable: lexical fallback.
        assertTrue(compareVersions("trash", "1.0.0") > 0)
    }
}