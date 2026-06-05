package com.rokidgames.headpong

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Single-View Venty HUD host.
 *
 * Starts directly in Venty BLE mode — no menu, no other games.
 *   UP/DOWN  → temp ±1
 *   TAP      → toggle heater
 *   D-TAP    → reconnect
 *
 * Renders into an 80×120 pixel bitmap, blitted with
 * 10 % top + 10 % bottom padding.
 */
class GameHostView(ctx: Context) : View(ctx) {

    // ---------- Pixel-Bitmap ----------
    private val srcW = 80
    private val srcH = 120
    private val pixelBmp = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
    private val pixelCanvas = Canvas(pixelBmp)
    private val srcRect = Rect(0, 0, srcW, srcH)
    private val blitPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val bgPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

    // ---------- Paints ----------
    private val pxFill = Paint().apply { isAntiAlias = false; color = GREEN_HI; style = Paint.Style.FILL }
    private val pxDim  = Paint().apply { isAntiAlias = false; color = GREEN_LO; style = Paint.Style.FILL }
    private val textPaint = Paint().apply {
        isAntiAlias = false; color = GREEN_HI; textSize = 6f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val textBig = Paint().apply {
        isAntiAlias = false; color = GREEN_HI; textSize = 10f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    // ---------- Venty ----------
    private val ventyBle = VentyBleService(ctx)
    private val ventyHud = VentyHud(srcW, srcH, pxFill, pxDim, textPaint, textBig)

    private var lastFrameNs = 0L

    // ---------- Touch ----------
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownMs = 0L

    init {
        ventyHud.bleService = ventyBle
        ventyHud.init()
    }

    // =====================================================================
    // Public API for MainActivity
    // =====================================================================

    fun onUp()    { ventyHud.tempUp() }
    fun onDown()  { ventyHud.tempDown() }
    fun onPrimary() {
        if (ventyBle.connState.value == VentyBleService.ConnState.DISCONNECTED)
            ventyBle.connect()
        else
            ventyHud.toggleHeat()
    }
    fun onBack() {
        ventyBle.disconnect()
        (context as? android.app.Activity)?.finish()
    }

    // =====================================================================
    // Touch-Fallback
    // =====================================================================
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = e.x; touchDownY = e.y
                touchDownMs = SystemClock.elapsedRealtime()
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - touchDownX
                val dy = e.y - touchDownY
                val dms = SystemClock.elapsedRealtime() - touchDownMs
                val moved = abs(dx) > 30 || abs(dy) > 30
                if (!moved && dms < 350) {
                    onPrimary()
                } else if (abs(dx) > abs(dy)) {
                    // left/right unused
                } else if (abs(dy) > 30) {
                    if (dy > 0) onDown() else onUp()
                }
            }
        }
        return true
    }

    // =====================================================================
    // Render-Loop
    // =====================================================================
    override fun onDraw(canvas: Canvas) {
        val nowNs = System.nanoTime()
        lastFrameNs = nowNs

        canvas.drawPaint(bgPaint)
        pixelCanvas.drawColor(Color.BLACK)

        ventyHud.update()
        ventyHud.draw(pixelCanvas, 0)

        // Scale bitmap to ~25% of the display so text stays small and readable.
        // The 80×120 canvas is rendered at a fraction of screen size,
        // centered horizontally, positioned in the upper portion vertically.
        val scale = (width / srcW.toFloat()) * 0.50f
        val dw = (srcW * scale).toInt().coerceAtLeast(40)
        val dh = (srcH * scale).toInt().coerceAtLeast(60)
        val left = (width - dw) / 2
        val top = (height * 0.05f).toInt()
        val dst = Rect(left, top, left + dw, top + dh)
        canvas.drawBitmap(pixelBmp, srcRect, dst, blitPaint)

        postInvalidateOnAnimation()
    }

    companion object {
        private const val GREEN_HI = 0xFF00FF00.toInt()
        private const val GREEN_LO = 0xFF008800.toInt()
    }
}
