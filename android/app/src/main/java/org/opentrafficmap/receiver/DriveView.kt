package org.opentrafficmap.receiver

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Full-screen animated drive-mode overlay.
 *
 * Draws a perspective road animating at the current GPS speed, a traffic-light
 * card when an RSU is within 400 m ahead, nearby vehicle silhouettes and
 * DENM condition banners.
 */
class DriveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── State updated from MainActivity ──────────────────────────────────────
    @Volatile var speedKmh: Float = 0f
    private var nearestLight: LightData? = null
    private val nearbyVehicles = ArrayList<NearbyVehicle>(8)
    private var conditionText: String? = null

    // ── Road animation ────────────────────────────────────────────────────────
    // World-space distances of the centre-lane dashes (metres, NEAR_PLANE … FAR_PLANE)
    private val dashDists = FloatArray(12) { NEAR_PLANE + it * ((FAR_PLANE - NEAR_PLANE) / 12f) }
    private var lastFrameNs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val frameRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtimeNanos()
            if (lastFrameNs != 0L) advanceDashes((now - lastFrameNs) * 1e-9f)
            lastFrameNs = now
            invalidate()
            handler.postDelayed(this, 16L)
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────────
    data class LightData(
        val phase: SpatTemParser.Phase,
        val distanceM: Float,
        val secsLeft: Int?,
    )
    data class NearbyVehicle(
        val relBearingDeg: Float,
        val distanceM: Float,
        val speedKmh: Float,
    )

    // ── Paints ────────────────────────────────────────────────────────────────
    private val skyTopPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val roadFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E26.toInt(); style = Paint.Style.FILL
    }
    private val dashPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt(); style = Paint.Style.FILL
    }
    private val edgePaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val centrePaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEEEEEE.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f; alpha = 160
    }
    private val speedBigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val speedUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt(); textAlign = Paint.Align.CENTER
    }
    private val cardPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0101018.toInt() }
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2A38.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val redOnPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val yellowOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFDD835.toInt() }
    private val greenOnPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF43A047.toInt() }
    private val dimCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202028.toInt() }
    private val countdownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val distPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt(); textAlign = Paint.Align.CENTER
    }
    private val aheadPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF8F00.toInt(); style = Paint.Style.STROKE
    }
    private val oncomPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF44336.toInt(); style = Paint.Style.STROKE
    }
    private val condBgPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCB71C1C.toInt() }
    private val condTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // ── Public API ────────────────────────────────────────────────────────────
    fun start()  { lastFrameNs = 0L; handler.post(frameRunnable) }
    fun stop()   { handler.removeCallbacks(frameRunnable) }

    fun updateLight(data: LightData?) { nearestLight = data }
    fun setCondition(text: String?)   { conditionText = text }
    fun updateVehicles(list: List<NearbyVehicle>) {
        synchronized(nearbyVehicles) { nearbyVehicles.clear(); nearbyVehicles.addAll(list) }
    }

    // ── Animation ─────────────────────────────────────────────────────────────
    private fun advanceDashes(dt: Float) {
        val v = speedKmh / 3.6f
        for (i in dashDists.indices) {
            dashDists[i] -= v * dt
            if (dashDists[i] < NEAR_PLANE) dashDists[i] += FAR_PLANE - NEAR_PLANE
        }
    }

    // ── Draw ──────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val W = width.toFloat(); val H = height.toFloat()
        if (W < 10 || H < 10) return
        val horizY = H * HORIZ_FRAC
        canvas.drawColor(0xFF050510.toInt())    // full background — avoids any gaps
        drawSky(canvas, W, H, horizY)
        drawRoad(canvas, W, H, horizY)
        synchronized(nearbyVehicles) { drawVehicles(canvas, W, H, horizY) }
        drawSpeed(canvas, W, H)
        nearestLight?.let { drawTrafficLight(canvas, W, H, it) }
        conditionText?.let { drawCondition(canvas, W, H, it) }
    }

    // ── Sky ───────────────────────────────────────────────────────────────────
    private fun drawSky(canvas: Canvas, W: Float, H: Float, horizY: Float) {
        skyTopPaint.shader = LinearGradient(0f, 0f, 0f, horizY,
            intArrayOf(0xFF020208.toInt(), 0xFF08081A.toInt(), 0xFF10102E.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, W, horizY, skyTopPaint)

        // Warm horizon glow
        val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        gp.shader = RadialGradient(W / 2f, horizY, W * 0.6f,
            intArrayOf(0x40FF9020, 0x15FF5010, 0x00000000),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, horizY - H * 0.10f, W, horizY + H * 0.03f, gp)
    }

    // ── Road ──────────────────────────────────────────────────────────────────
    private fun drawRoad(canvas: Canvas, W: Float, H: Float, horizY: Float) {
        // Road trapezoid
        val path = Path().apply {
            moveTo(W * BL, H);   lineTo(W * TL, horizY)
            lineTo(W * TR, horizY); lineTo(W * BR, H); close()
        }
        canvas.drawPath(path, roadFillPaint)

        // Headlight centre glow
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.shader = LinearGradient(W / 2f, horizY, W / 2f, H,
            intArrayOf(0x00252540, 0x20AAAAEE, 0x00252540),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, glow)

        // Edge lines (white, solid)
        edgePaint.strokeWidth = 3.5f
        canvas.drawLine(W * BL, H, W * TL, horizY, edgePaint)
        canvas.drawLine(W * BR, H, W * TR, horizY, edgePaint)

        // Centre lane divider (dashed appearance via thin solid line, animation on dashes)
        centrePaint.strokeWidth = 2f
        canvas.drawLine(W / 2f, H, W / 2f, horizY, centrePaint)

        // Animated dashes in right (own) lane
        for (d in dashDists) {
            if (d < NEAR_PLANE || d > FAR_PLANE) continue
            val t   = (NEAR_PLANE / d).coerceIn(0f, 1f)
            val sy  = horizY + (H - horizY) * t
            val rL  = lerp(W * TL, W * BL, t)
            val rR  = lerp(W * TR, W * BR, t)
            val rW  = rR - rL
            val dW  = rW * 0.042f
            val dH  = (H - horizY) * 0.07f * t
            val cx  = lerp(W / 2f, rR, 0.5f)   // centre of right lane
            canvas.drawRect(cx - dW / 2, sy - dH / 2, cx + dW / 2, sy + dH / 2, dashPaint)
        }
    }

    // ── Vehicles ──────────────────────────────────────────────────────────────
    private fun drawVehicles(canvas: Canvas, W: Float, H: Float, horizY: Float) {
        val behind = nearbyVehicles.firstOrNull { it.relBearingDeg in 150f..210f && it.distanceM < 70f }
        behind?.let { drawBehindBadge(canvas, W, H) }

        for (v in nearbyVehicles) {
            if (v.distanceM > 200f) continue
            val t  = (NEAR_PLANE / v.distanceM.coerceAtLeast(NEAR_PLANE)).coerceIn(0.03f, 1f)
            val sy = horizY + (H - horizY) * t
            val rL = lerp(W * TL, W * BL, t)
            val rR = lerp(W * TR, W * BR, t)
            val rW = rR - rL
            val cW = rW * 0.20f; val cH = cW * 1.65f
            when {
                v.relBearingDeg < 35f || v.relBearingDeg > 325f -> {
                    aheadPaint.strokeWidth = (cW * 0.10f).coerceAtLeast(2f)
                    aheadPaint.alpha = if (v.distanceM < 40f) 255 else 180
                    drawCarOutline(canvas, lerp(W / 2f, rR, 0.5f), sy, cW, cH, aheadPaint)
                    if (v.distanceM < 35f) {
                        // Danger fill tint
                        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FF8F00 }
                        drawCarOutline(canvas, lerp(W / 2f, rR, 0.5f), sy, cW, cH, fp)
                    }
                }
                v.relBearingDeg in 145f..215f -> {
                    oncomPaint.strokeWidth = (cW * 0.10f).coerceAtLeast(2f)
                    drawCarOutline(canvas, lerp(rL, W / 2f, 0.5f), sy, cW, cH, oncomPaint)
                    // Headlight glow
                    val hp = Paint(Paint.ANTI_ALIAS_FLAG)
                    hp.shader = RadialGradient(lerp(rL, W / 2f, 0.5f), sy - cH * 0.5f, cW * 1.2f,
                        intArrayOf(0x40FFFFFF, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawCircle(lerp(rL, W / 2f, 0.5f), sy - cH * 0.5f, cW * 1.2f, hp)
                }
            }
        }
    }

    private fun drawCarOutline(canvas: Canvas, cx: Float, cy: Float, cw: Float, ch: Float, p: Paint) {
        canvas.drawRoundRect(RectF(cx - cw/2, cy - ch*0.45f, cx + cw/2, cy + ch*0.45f), cw*0.18f, cw*0.18f, p)
        val roof = Path().apply {
            moveTo(cx - cw*0.40f, cy - ch*0.08f); lineTo(cx - cw*0.28f, cy - ch*0.40f)
            lineTo(cx + cw*0.28f, cy - ch*0.40f); lineTo(cx + cw*0.40f, cy - ch*0.08f)
        }
        canvas.drawPath(roof, p)
    }

    private fun drawBehindBadge(canvas: Canvas, W: Float, H: Float) {
        val cx = W / 2f; val cy = H - 28f; val r = 20f
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFF8F00.toInt() })
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt(); textAlign = Paint.Align.CENTER
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("▼", cx, cy + 6f, tp)
    }

    // ── Speed ─────────────────────────────────────────────────────────────────
    private fun drawSpeed(canvas: Canvas, W: Float, H: Float) {
        val cx = W / 2f; val cy = H * 0.78f
        speedBigPaint.textSize  = min(W, H) * 0.20f
        speedUnitPaint.textSize = min(W, H) * 0.048f
        canvas.drawText("%.0f".format(speedKmh), cx, cy, speedBigPaint)
        canvas.drawText("km/h", cx, cy + speedUnitPaint.textSize * 1.15f, speedUnitPaint)
    }

    // ── Traffic Light ─────────────────────────────────────────────────────────
    private fun drawTrafficLight(canvas: Canvas, W: Float, H: Float, light: LightData) {
        val cardW = min(W * 0.36f, 170f)
        val cirR  = cardW * 0.155f
        val gap   = cirR * 0.50f
        val vpad  = cirR * 0.75f
        val hasCount = light.secsLeft != null && light.secsLeft > 0
        val cardH = vpad + (cirR * 2 + gap) * 3 - gap + vpad + (if (hasCount) cirR * 0.85f else 0f)
        val cx    = W / 2f
        val left  = cx - cardW / 2; val top = H * 0.045f

        val rect  = RectF(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(rect, 18f, 18f, cardPaint)
        canvas.drawRoundRect(rect, 18f, 18f, cardBorderPaint)

        val yR = top + vpad + cirR
        val yY = yR + cirR * 2 + gap
        val yG = yY + cirR * 2 + gap

        val isRed    = light.phase == SpatTemParser.Phase.RED
        val isYellow = light.phase == SpatTemParser.Phase.YELLOW
        val isGreen  = light.phase == SpatTemParser.Phase.GREEN
        val isUnknown = !isRed && !isYellow && !isGreen

        canvas.drawCircle(cx, yR, cirR, if (isRed)    redOnPaint    else dimCirclePaint)
        canvas.drawCircle(cx, yY, cirR, if (isYellow) yellowOnPaint else dimCirclePaint)
        canvas.drawCircle(cx, yG, cirR, if (isGreen)  greenOnPaint  else dimCirclePaint)

        // Glow on active signal
        if (!isUnknown) {
            val activeY = if (isRed) yR else if (isYellow) yY else yG
            val gc = if (isRed) 0x55E53935 else if (isYellow) 0x55FDD835 else 0x5543A047
            val gp = Paint(Paint.ANTI_ALIAS_FLAG)
            gp.shader = RadialGradient(cx, activeY, cirR * 2.4f, intArrayOf(gc, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, activeY, cirR * 2.4f, gp)
        }

        // Countdown
        if (hasCount) {
            countdownPaint.textSize = cirR * 0.95f
            val countY = yG + cirR * 2 + vpad * 0.25f + countdownPaint.textSize * 0.9f
            countdownPaint.color = if (isRed) 0xFFE53935.toInt() else if (isYellow) 0xFFFDD835.toInt() else 0xFF43A047.toInt()
            canvas.drawText("${light.secsLeft}s", cx, countY, countdownPaint)
        }

        // Distance label
        distPaint.textSize = min(W, H) * 0.032f
        canvas.drawText("%.0f m".format(light.distanceM), cx, top + cardH + distPaint.textSize * 1.5f, distPaint)
    }

    // ── Condition Banner ──────────────────────────────────────────────────────
    private fun drawCondition(canvas: Canvas, W: Float, H: Float, text: String) {
        val bh = H * 0.065f; val by = H * 0.875f
        canvas.drawRoundRect(RectF(W * 0.05f, by, W * 0.95f, by + bh), 10f, 10f, condBgPaint)
        condTextPaint.textSize = bh * 0.48f
        canvas.drawText("⚠  $text", W / 2f, by + bh * 0.68f, condTextPaint)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        private const val NEAR_PLANE = 3f
        private const val FAR_PLANE  = 130f
        private const val HORIZ_FRAC = 0.36f  // horizon at 36 % from top of DriveView
        // Road trapezoid corners (fraction of view width)
        private const val BL = 0.05f   // bottom-left
        private const val BR = 0.95f   // bottom-right
        private const val TL = 0.36f   // top-left  (at horizon)
        private const val TR = 0.64f   // top-right (at horizon)
    }
}
