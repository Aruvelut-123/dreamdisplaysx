package com.dreamdisplayx.util.natives

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativesCacheStampTest {

    // ── tagOf ──────────────────────────────────────────────────────────────

    @Test
    fun tagOfReadsTheLastPathSegmentOfAReleaseBase() {
        val base = "https://github.com/Aruvelut-123/dreamdisplaysx/releases/download/natives-7f303c9-1787719531"
        assertEquals("natives-7f303c9-1787719531", NativesCacheStamp.tagOf(base))
    }

    @Test
    fun tagOfToleratesATrailingSlash() {
        val base = "https://github.com/o/r/releases/download/natives-abc-123/"
        assertEquals("natives-abc-123", NativesCacheStamp.tagOf(base))
    }

    @Test
    fun tagOfToleratesSurroundingWhitespace() {
        // Read back from a file that a user may have edited, so the newline trimmed on write can
        // come back with padding.
        assertEquals(
            "natives-abc-123",
            NativesCacheStamp.tagOf("  https://github.com/o/r/releases/download/natives-abc-123\n"),
        )
    }

    @Test
    fun tagOfRejectsBlankInput() {
        assertNull(NativesCacheStamp.tagOf(""))
        assertNull(NativesCacheStamp.tagOf("   "))
        assertNull(NativesCacheStamp.tagOf("/"))
    }

    @Test
    fun tagOfRejectsBareReleasePages() {
        // These have no tag segment, so accepting them would make every cache look current.
        assertNull(NativesCacheStamp.tagOf("https://github.com/o/r/releases"))
        assertNull(NativesCacheStamp.tagOf("https://github.com/o/r/releases/download"))
        assertNull(NativesCacheStamp.tagOf("https://github.com/o/r/releases/latest"))
    }

    // ── isFresh ────────────────────────────────────────────────────────────

    @Test
    fun isFreshMatchesWhenTheStampedTagIsTheExpectedOne() {
        assertTrue(NativesCacheStamp.isFresh("natives-abc-123", "natives-abc-123"))
    }

    @Test
    fun isFreshRejectsADifferentTag() {
        // This is the whole point: a mod update that points the manifest at a newer natives release
        // must invalidate the cache, otherwise existing installs keep the old libvlc forever.
        assertFalse(NativesCacheStamp.isFresh("natives-old-111", "natives-new-222"))
    }

    @Test
    fun isFreshRejectsAMissingStamp() {
        // An unstamped cache predates stamping; re-download once rather than trusting it.
        assertFalse(NativesCacheStamp.isFresh(null, "natives-new-222"))
    }

    @Test
    fun isFreshTrustsTheCacheWhenTheManifestHasNoUsableTag() {
        // An unusual or broken manifest URL leaves us unable to know what is current. Keeping the
        // previous behaviour is better than re-downloading hundreds of megabytes on every launch.
        assertTrue(NativesCacheStamp.isFresh(null, null))
        assertTrue(NativesCacheStamp.isFresh("natives-abc-123", null))
    }

    // ── legacyCacheIsFresh ─────────────────────────────────────────────────

    @Test
    fun aLegacyCacheIsOnlyReusedWhenTheManifestCarriesNoTag() {
        assertFalse(NativesCacheStamp.legacyCacheIsFresh("natives-new-222"))
        assertTrue(NativesCacheStamp.legacyCacheIsFresh(null))
    }

    @Test
    fun theStampFileNameIsStable() {
        // Renaming this file would silently invalidate every existing cache on the next launch.
        assertEquals("natives-stamp.txt", NativesCacheStamp.FILE_NAME)
    }
}