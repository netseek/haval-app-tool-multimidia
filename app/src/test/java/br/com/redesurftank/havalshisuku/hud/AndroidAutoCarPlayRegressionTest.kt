package br.com.redesurftank.havalshisuku.hud

import br.com.redesurftank.havalshisuku.hud.HudOverlayModel.Config
import br.com.redesurftank.havalshisuku.hud.HudOverlayModel.Layout
import br.com.redesurftank.havalshisuku.hud.HudOverlayModel.TbtStyle
import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry
import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry.Directions
import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry.LaneDirection
import br.com.redesurftank.havalshisuku.navigation.CarPlayHudPolicy
import br.com.redesurftank.havalshisuku.navigation.RawCarPlayGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the CarPlay port's two touch points in the SHARED Android Auto HUD path:
 *   1. HudOverlayModel.tbtFor gained a `textOnly -> TbtStyle.TEXT` branch (and
 *      HudOverlayRenderer.drawTextGuidance is invoked ONLY for TbtStyle.TEXT). These tests assert
 *      TEXT is reachable ONLY for the CarPlay text-only shape (no maneuver + no numeric distance),
 *      so no normal or partial Android Auto guidance is ever diverted off its original HERO/
 *      COMPACT/FAR/AR path.
 *   2. AndroidAutoClusterController now routes AA directions through
 *      CarPlayExperimentalHud.onAndroidAuto(...) instead of calling HudOverlayController directly.
 *      CarPlayExperimentalHud.onAndroidAuto delegates to CarPlayHudPolicy.onAndroidAuto and then
 *      forwards CarPlayHudPolicy.snapshot(...) to HudOverlayController.onDirections. These tests pin
 *      that policy contract: an active AA frame is forwarded UNCHANGED and CarPlay never overrides a
 *      live AA session; a stop forwards the inactive frame; and the conservative known-inactive
 *      signal never surfaces CarPlay while AA might still be active.
 */
class AndroidAutoCarPlayRegressionTest {

    private val nav = Config(layout = Layout.NAVIGATION)
    private val ar = Config(layout = Layout.AR)

    // ---- 1. TbtStyle.TEXT gating: normal / partial AA must NOT become TEXT ----

    @Test fun `normal complete AA keeps its usual HUD styles, never TEXT`() {
        val near = Directions(active = true, distance = "250 m", distanceM = 250, turn = "TURN_LEFT", turnId = 3)
        val far = near.copy(distance = "1.8 km", distanceM = 1_800)
        val lanes = near.copy(lanes = listOf(listOf(LaneDirection(1, true))))

        assertEquals(TbtStyle.HERO, HudOverlayModel.tbtFor(near, nav)!!.style)
        assertEquals(TbtStyle.FAR, HudOverlayModel.tbtFor(far, nav)!!.style)
        assertEquals(TbtStyle.COMPACT, HudOverlayModel.tbtFor(lanes, nav)!!.style)
        assertEquals(TbtStyle.AR, HudOverlayModel.tbtFor(near, ar)!!.style)
        for (d in listOf(near, far, lanes)) {
            assertNotEquals(TbtStyle.TEXT, HudOverlayModel.tbtFor(d, nav)!!.style)
            assertNotEquals(TbtStyle.TEXT, HudOverlayModel.tbtFor(d, ar)!!.style)
        }
    }

    @Test fun `partial AA (missing one field) stays on the normal path, never TEXT`() {
        val base = Directions(active = true, distance = "250 m", distanceM = 250, turn = "TURN_LEFT", turnId = 3,
            street = "Av. Brasil")
        // AA pushes a new step before its distance arrives: maneuver present, distance absent.
        val noDistance = base.copy(distance = "", distanceM = null)
        // Street not yet resolved.
        val noStreet = base.copy(street = "")
        // Maneuver token absent but a numeric distance is still present (not the text-only shape).
        val noTurn = base.copy(turn = "", turnId = null)
        // Maneuver absent with an id still set (still not the text-only shape).
        val turnIdOnly = base.copy(turn = "", distanceM = null, distance = "")

        for (d in listOf(noDistance, noStreet, noTurn, turnIdOnly)) {
            val navStyle = HudOverlayModel.tbtFor(d, nav)!!.style
            val arStyle = HudOverlayModel.tbtFor(d, ar)!!.style
            assertNotEquals("nav style must not be TEXT for $d", TbtStyle.TEXT, navStyle)
            assertNotEquals("ar style must not be TEXT for $d", TbtStyle.TEXT, arStyle)
        }
        // Missing-distance maneuver keeps the big arrow in NAVIGATION and stays AR in AR layout.
        assertEquals(TbtStyle.HERO, HudOverlayModel.tbtFor(noDistance, nav)!!.style)
        assertEquals(TbtStyle.AR, HudOverlayModel.tbtFor(noDistance, ar)!!.style)
    }

    @Test fun `inactive AA produces no frame and does not crash`() {
        assertNull(HudOverlayModel.tbtFor(Directions(active = false)))
        assertNull(HudOverlayModel.tbtFor(Directions(active = false), nav))
        assertNull(HudOverlayModel.tbtFor(Directions(active = false), ar))
    }

    @Test fun `TEXT triggers ONLY for the CarPlay text-only shape`() {
        // The CarPlay text-only shape: active, a road + display distance string, but NO maneuver
        // token, NO turnId and NO numeric distanceM.
        val textOnly = Directions(active = true, street = "Rua das Flores", distance = "300 m",
            distanceM = null, turn = "", turnId = null)
        assertEquals(TbtStyle.TEXT, HudOverlayModel.tbtFor(textOnly, nav)!!.style)
        // textOnly forces the plain box even under AR, and never invents an arrow.
        val arTbt = HudOverlayModel.tbtFor(textOnly, ar)!!
        assertEquals(TbtStyle.TEXT, arTbt.style)
        assertNull(arTbt.arAngle)
        assertEquals("Rua das Flores", arTbt.street)
        assertEquals("300", arTbt.distanceValue)

        // Any single piece of AA maneuver/distance evidence pulls it back off TEXT.
        assertNotEquals(TbtStyle.TEXT, HudOverlayModel.tbtFor(textOnly.copy(turn = "TURN_LEFT"), nav)!!.style)
        assertNotEquals(TbtStyle.TEXT, HudOverlayModel.tbtFor(textOnly.copy(turnId = 9), nav)!!.style)
        assertNotEquals(TbtStyle.TEXT, HudOverlayModel.tbtFor(textOnly.copy(distanceM = 300), nav)!!.style)
    }

    // ---- 2. Forwarding contract CarPlayExperimentalHud relies on (CarPlayHudPolicy) ----

    private fun enabledPolicy() = CarPlayHudPolicy().apply {
        begin("capture"); connect("capture", 1); setEnabled(true)
        onAndroidAutoAvailability(false, 100); onPassiveAndroidAutoAvailability(false, 100)
    }

    private fun carPlayRaw(state: Int = 1) =
        RawCarPlayGuidance(32, "150", 2, "Via de teste", "2,2", 0, 480, state, 0, 0, -96)

    @Test fun `active AA is forwarded to the HUD unchanged`() {
        val policy = CarPlayHudPolicy()
        val aa = Directions(active = true, street = "Av. Brasil", distance = "250 m", distanceM = 250,
            turn = "TURN_RIGHT", turnId = 5)
        policy.onAndroidAuto(aa)
        // This is exactly what CarPlayExperimentalHud publishes to HudOverlayController.onDirections:
        // the live AA frame, value-identical (all fields preserved), never replaced by CarPlay text.
        assertEquals(aa, policy.snapshot(100))
        assertEquals(aa.toJson(), policy.snapshot(100).toJson())
    }

    @Test fun `CarPlay text never overrides a live AA session`() {
        val policy = enabledPolicy()
        val aa = Directions(active = true, street = "AA", distance = "20 m", distanceM = 20,
            turn = "ROUNDABOUT", turnId = 42)
        policy.onAndroidAuto(aa)
        policy.onCarPlay("capture", 1, carPlayRaw(), 100)
        assertEquals(aa, policy.snapshot(100))
    }

    @Test fun `AA stop clears the HUD and never leaves stale AA directions`() {
        // No CarPlay eligibility here (policy never enabled/captured), so this isolates the AA
        // stop contract the port's reset hook relies on: onAndroidAuto(inactive()) replaces the
        // previously-forwarded active AA frame with the inactive frame, rather than leaving the
        // HUD on the old directions.
        val policy = CarPlayHudPolicy()
        val aa = Directions(active = true, street = "Av. Brasil", distance = "80 m", distanceM = 80,
            turn = "TURN_RIGHT", turnId = 7)
        policy.onAndroidAuto(aa)
        assertTrue(policy.snapshot(100).active)
        policy.onAndroidAuto(AndroidAutoNavigationTelemetry.inactive())
        assertFalse(policy.snapshot(101).active)
    }

    @Test fun `conservative known-inactive signal keeps AA priority until AA is confirmed off`() {
        // A fresh session has no confirmed AA-inactive evidence: CarPlay stays suppressed, so the
        // HUD keeps receiving the (inactive) AA frame rather than CarPlay text. This matches the
        // port omitting the AA-availability push hook: the signal degrades conservatively.
        val policy = CarPlayHudPolicy().apply { begin("capture"); connect("capture", 1); setEnabled(true) }
        policy.onCarPlay("capture", 1, carPlayRaw(), 100)
        assertFalse(policy.isAndroidAutoKnownInactive(100))
        assertFalse(policy.snapshot(100).active)

        // Positive control: once AA is explicitly confirmed inactive, CarPlay text is allowed.
        policy.onAndroidAutoAvailability(false, 100)
        policy.onPassiveAndroidAutoAvailability(false, 100)
        assertTrue(policy.isAndroidAutoKnownInactive(100))
        assertTrue(policy.snapshot(100).active)
    }
}
