package br.com.redesurftank.havalshisuku.managers

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayAppLauncherAndroidAutoClusterGuardTest {
    private val impulsePackage = "br.com.redesurftank.havalshisuku"

    @Test
    fun normalDisplayZeroWindowUsesAndroidAutoFullscreenFocusGuard() {
        assertEquals(
            "FULLSCREEN_AND_FOCUS",
            DisplayAppLauncher.resolveAndroidAutoWindowFocusGuardActionForTest(
                packageName = "com.android.settings",
                selfPackageName = impulsePackage
            )
        )
    }

    @Test
    fun nativeDisplayZeroPanelsUsePassiveAndroidAutoWindowGuard() {
        assertEquals(
            "VERIFY_ONLY",
            DisplayAppLauncher.resolveAndroidAutoWindowFocusGuardActionForTest(
                packageName = "com.beantechs.hvac",
                selfPackageName = impulsePackage
            )
        )
        assertEquals(
            "VERIFY_ONLY",
            DisplayAppLauncher.resolveAndroidAutoWindowFocusGuardActionForTest(
                packageName = "com.beantechs.avm",
                selfPackageName = impulsePackage
            )
        )
        assertEquals(
            "VERIFY_ONLY",
            DisplayAppLauncher.resolveAndroidAutoWindowFocusGuardActionForTest(
                packageName = "com.beantechs.launcher",
                selfPackageName = impulsePackage
            )
        )
        assertEquals(
            "VERIFY_ONLY",
            DisplayAppLauncher.resolveAndroidAutoWindowFocusGuardActionForTest(
                packageName = "com.beantechs.applist",
                selfPackageName = impulsePackage
            )
        )
        assertEquals(
            "VERIFY_ONLY",
            DisplayAppLauncher.resolveAndroidAutoWindowFocusGuardActionForTest(
                packageName = impulsePackage,
                selfPackageName = impulsePackage
            )
        )
    }

    @Test
    fun projectionPackagesDoNotTriggerAndroidAutoWindowGuard() {
        assertNull(
            DisplayAppLauncher.resolveAndroidAutoWindowFocusGuardActionForTest(
                packageName = "com.ts.androidauto.app",
                selfPackageName = impulsePackage
            )
        )
        assertNull(
            DisplayAppLauncher.resolveAndroidAutoWindowFocusGuardActionForTest(
                packageName = "com.ts.carplay.app",
                selfPackageName = impulsePackage
            )
        )
    }

    @Test
    fun androidAutoMediaKeysRequireClusterProjectionAndMediaAction() {
        assertTrue(
            DisplayAppLauncher.shouldHandleAndroidAutoMediaControlKeyForTest(
                isAndroidAutoClusterActive = true,
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                action = KeyEvent.ACTION_DOWN,
                now = 1_000L,
                lastKeyCode = 0,
                lastHandledAt = 0L
            )
        )

        assertFalse(
            DisplayAppLauncher.shouldHandleAndroidAutoMediaControlKeyForTest(
                isAndroidAutoClusterActive = false,
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                action = KeyEvent.ACTION_DOWN,
                now = 1_000L,
                lastKeyCode = 0,
                lastHandledAt = 0L
            )
        )

        assertTrue(
            DisplayAppLauncher.shouldHandleAndroidAutoMediaControlKeyForTest(
                isAndroidAutoClusterActive = true,
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                action = KeyEvent.ACTION_UP,
                now = 1_000L,
                lastKeyCode = 0,
                lastHandledAt = 0L
            )
        )

        assertFalse(
            DisplayAppLauncher.shouldHandleAndroidAutoMediaControlKeyForTest(
                isAndroidAutoClusterActive = true,
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                action = 2,
                now = 1_000L,
                lastKeyCode = 0,
                lastHandledAt = 0L
            )
        )

        assertFalse(
            DisplayAppLauncher.shouldHandleAndroidAutoMediaControlKeyForTest(
                isAndroidAutoClusterActive = true,
                keyCode = KeyEvent.KEYCODE_ENTER,
                action = KeyEvent.ACTION_DOWN,
                now = 1_000L,
                lastKeyCode = 0,
                lastHandledAt = 0L
            )
        )
    }

    @Test
    fun androidAutoMediaKeysAreDebouncedByKeyCode() {
        assertFalse(
            DisplayAppLauncher.shouldHandleAndroidAutoMediaControlKeyForTest(
                isAndroidAutoClusterActive = true,
                keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                action = KeyEvent.ACTION_DOWN,
                now = 1_300L,
                lastKeyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                lastHandledAt = 1_000L,
                cooldownMs = 650L
            )
        )

        assertTrue(
            DisplayAppLauncher.shouldHandleAndroidAutoMediaControlKeyForTest(
                isAndroidAutoClusterActive = true,
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                action = KeyEvent.ACTION_DOWN,
                now = 1_300L,
                lastKeyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                lastHandledAt = 1_000L,
                cooldownMs = 650L
            )
        )
    }

    @Test
    fun clusterMediaCommandMapsToAndroidAutoMediaKeys() {
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            DisplayAppLauncher.mapAndroidAutoClusterMediaCommandForTest(1)
        )
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_NEXT,
            DisplayAppLauncher.mapAndroidAutoClusterMediaCommandForTest(2)
        )
        assertNull(DisplayAppLauncher.mapAndroidAutoClusterMediaCommandForTest(99))
    }

    @Test
    fun androidAutoMediaKeysMapToNativeAapHardkeyOrdinals() {
        assertEquals(
            10,
            DisplayAppLauncher.mapAndroidAutoMediaKeyToAapHardkeyOrdinalForTest(
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        assertEquals(
            9,
            DisplayAppLauncher.mapAndroidAutoMediaKeyToAapHardkeyOrdinalForTest(
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        assertEquals(
            6,
            DisplayAppLauncher.mapAndroidAutoMediaKeyToAapHardkeyOrdinalForTest(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            )
        )
        assertEquals(
            7,
            DisplayAppLauncher.mapAndroidAutoMediaKeyToAapHardkeyOrdinalForTest(
                KeyEvent.KEYCODE_MEDIA_PLAY
            )
        )
        assertEquals(
            8,
            DisplayAppLauncher.mapAndroidAutoMediaKeyToAapHardkeyOrdinalForTest(
                KeyEvent.KEYCODE_MEDIA_PAUSE
            )
        )
        assertNull(
            DisplayAppLauncher.mapAndroidAutoMediaKeyToAapHardkeyOrdinalForTest(
                KeyEvent.KEYCODE_ENTER
            )
        )
    }

    @Test
    fun androidAutoMediaKeysMapToOemInputFallbackCodes() {
        assertEquals(
            1003,
            DisplayAppLauncher.mapAndroidAutoMediaKeyToOemInputKeyCodeForTest(
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        assertEquals(
            1002,
            DisplayAppLauncher.mapAndroidAutoMediaKeyToOemInputKeyCodeForTest(
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        assertEquals(
            1004,
            DisplayAppLauncher.mapAndroidAutoMediaKeyToOemInputKeyCodeForTest(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            )
        )
        assertNull(
            DisplayAppLauncher.mapAndroidAutoMediaKeyToOemInputKeyCodeForTest(
                KeyEvent.KEYCODE_ENTER
            )
        )
    }

    @Test
    fun androidAutoPreviousNextUseSingleOemRouteAfterDuplicateSkips() {
        assertTrue(DisplayAppLauncher.isAndroidAutoOemInputMediaFallbackEnabledForTest())
        assertTrue(
            DisplayAppLauncher.shouldUseAndroidAutoOemOnlyMediaRouteForTest(
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        assertTrue(
            DisplayAppLauncher.shouldUseAndroidAutoOemOnlyMediaRouteForTest(
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        assertFalse(
            DisplayAppLauncher.shouldUseAndroidAutoOemOnlyMediaRouteForTest(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            )
        )
    }

    @Test
    fun androidAutoPhysicalMediaKeysUseHeadunitNativeRouteOnly() {
        assertTrue(
            DisplayAppLauncher.shouldUseAndroidAutoHeadunitNativeRouteOnlyForTest(
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                isSteeringInput = true
            )
        )
        assertTrue(
            DisplayAppLauncher.shouldUseAndroidAutoHeadunitNativeRouteOnlyForTest(
                keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                isSteeringInput = true
            )
        )
        assertFalse(
            DisplayAppLauncher.shouldUseAndroidAutoHeadunitNativeRouteOnlyForTest(
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                isSteeringInput = false
            )
        )
        assertTrue(
            DisplayAppLauncher.shouldUseAndroidAutoHeadunitNativeRouteOnlyForTest(
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                isSteeringInput = true
            )
        )
        assertTrue(
            DisplayAppLauncher.shouldUseAndroidAutoHeadunitNativeRouteOnlyForTest(
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY,
                isSteeringInput = true
            )
        )
        assertTrue(
            DisplayAppLauncher.shouldUseAndroidAutoHeadunitNativeRouteOnlyForTest(
                keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE,
                isSteeringInput = true
            )
        )
        assertFalse(
            DisplayAppLauncher.shouldUseAndroidAutoHeadunitNativeRouteOnlyForTest(
                keyCode = KeyEvent.KEYCODE_ENTER,
                isSteeringInput = true
            )
        )
    }

    @Test
    fun androidAutoNativeStatusDescriptionsAreStable() {
        assertEquals(
            "ACTIVATED(3)",
            DisplayAppLauncher.describeAndroidAutoLinkStatusForTest(3)
        )
        assertEquals(
            "AAP_FRX(8)",
            DisplayAppLauncher.describeAndroidAutoLinkStatusForTest(8)
        )
        assertEquals(
            "PLAYING(1)",
            DisplayAppLauncher.describeAndroidAutoMusicStatusForTest(1)
        )
        assertEquals(
            "PAUSED(2)",
            DisplayAppLauncher.describeAndroidAutoMusicStatusForTest(2)
        )
    }

    @Test
    fun androidAutoOemInputFallbackEchoIsSkippedDuringBlockWindow() {
        assertTrue(
            DisplayAppLauncher.shouldSkipAndroidAutoOemInputFallbackEchoForTest(
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                action = KeyEvent.ACTION_UP,
                now = 2_000L,
                echoKeyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                echoBlockUntil = 3_000L
            )
        )
        assertFalse(
            DisplayAppLauncher.shouldSkipAndroidAutoOemInputFallbackEchoForTest(
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                action = KeyEvent.ACTION_UP,
                now = 3_001L,
                echoKeyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                echoBlockUntil = 3_000L
            )
        )
        assertFalse(
            DisplayAppLauncher.shouldSkipAndroidAutoOemInputFallbackEchoForTest(
                keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                action = KeyEvent.ACTION_UP,
                now = 2_000L,
                echoKeyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                echoBlockUntil = 3_000L
            )
        )
    }
}
