package br.com.redesurftank.havalshisuku.hud

import android.content.Context
import android.graphics.Canvas
import android.view.View
import br.com.redesurftank.havalshisuku.diagnostics.ClusterPersistentEventLogger
import br.com.redesurftank.havalshisuku.navigation.CarPlayExperimentalHud

/**
 * The HUD window's content: draws one [HudOverlayModel.Frame] through [HudOverlayRenderer] on a
 * transparent canvas, so the native HUD shows through everywhere else.
 */
class HudOverlayView(context: Context) : View(context) {
    private val renderer = HudOverlayRenderer()
    private var frame: HudOverlayModel.Frame? = null
    private var lastCarPlayFrameDiagnostic: String? = null

    /** Null clears the overlay. Redraws only when the frame actually changed. */
    fun show(next: HudOverlayModel.Frame?) {
        if (next == frame) return
        frame = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, frame)
        traceCarPlayFrame()
        // Chevrons animate: keep redrawing (~30 fps) only while they are up; the
        // "carregando mapa" bar sweeps slowly while nothing has arrived yet.
        if (frame?.chevrons?.animate == true) postInvalidateDelayed(CHEVRON_FRAME_MS)
        else if (frame?.tbt?.mapPending == true) postInvalidateDelayed(PENDING_FRAME_MS)
    }

    /** Trace the real HUD view, never a preview or navigation text. */
    private fun traceCarPlayFrame() {
        if (!CarPlayExperimentalHud.enabled || !isAttachedToWindow ||
            display?.displayId != HudOverlayModel.DISPLAY_ID) {
            lastCarPlayFrameDiagnostic = null
            return
        }
        val guidance = frame?.tbt
        val details = linkedMapOf<String, Any>(
            "display" to HudOverlayModel.DISPLAY_ID,
            "frame_present" to (frame != null),
            "guidance_present" to (guidance != null),
            "style" to (guidance?.style?.name ?: "NONE"),
            "street_present" to !guidance?.street.isNullOrBlank(),
            "distance_present" to !guidance?.distanceValue.isNullOrBlank(),
            "turn_hidden" to (guidance?.turnHidden ?: false),
            "radar_present" to (frame?.radar != null)
        )
        val signature = details.values.joinToString("|")
        if (signature != lastCarPlayFrameDiagnostic) {
            lastCarPlayFrameDiagnostic = signature
            ClusterPersistentEventLogger.log("carplay_hud_frame", details)
        }
    }

    private companion object {
        const val CHEVRON_FRAME_MS = 33L
        const val PENDING_FRAME_MS = 250L
    }
}
