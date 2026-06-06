package com.rokidgames.headpong

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock

/**
 * Venty vaporizer HUD — simple text-list layout matching the old menu style.
 *
 * Uses textBig (12px) for title, textPaint (8px) for all data rows.
 * 10px line spacing, just like the old menu.
 *
 * Input:
 *   UP/DOWN  → temp ±1°C
 *   TAP      → toggle heater on/off
 *   D-TAP    → disconnect + reconnect
 */
internal class VentyHud(
    private val srcW: Int,
    private val srcH: Int,
    private val hi: Paint,      // pxFill — bright green
    private val lo: Paint,      // pxDim — dim green
    private val tp: Paint,      // textPaint 8px
    private val tb: Paint       // textBig 12px
) {
    var bleService: VentyBleService? = null

    private var conn = VentyBleService.ConnState.IDLE
    private var st   = VentyState()
    private var info = VentyDeviceInfo()
    private var started = false
    private var blink = false
    private var blinkNs = 0L

    var gameOver = false; private set
    var score = 0; private set

    fun init() {
        gameOver = false
        started = true
        conn = VentyBleService.ConnState.IDLE
        st   = VentyState()
        info = VentyDeviceInfo()
        bleService?.connect()
    }

    fun update() {
        val svc = bleService ?: return
        conn = svc.connState.value
        st   = svc.ventyState.value
        info = svc.deviceInfo.value
        val now = System.nanoTime()
        if (now - blinkNs > 500_000_000L) { blink = !blink; blinkNs = now }
    }

    fun tempUp()   { bleService?.adjustTemp(5);  SoundEngine.click() }
    fun tempDown() { bleService?.adjustTemp(-5); SoundEngine.click() }
    fun toggleHeat() {
        if (conn == VentyBleService.ConnState.DISCONNECTED) {
            bleService?.connect(); SoundEngine.click()
        } else {
            bleService?.toggleHeater(); SoundEngine.confirm()
        }
    }

    fun draw(canvas: Canvas, @Suppress("UNUSED_PARAMETER") best: Int) {
        when {
            conn == VentyBleService.ConnState.CONNECTED -> drawLive(canvas)
            conn == VentyBleService.ConnState.DISCONNECTED && started -> drawLost(canvas)
            else -> drawScan(canvas)
        }
    }

    // ====================================================================
    // LIVE — simple text list like old menu
    // ====================================================================

    private fun drawLive(canvas: Canvas) {
        var y = 8f

        // Title
        val title = "VENTY"
        canvas.drawText(title, (srcW - tb.measureText(title)) / 2f, y, tb)
        y += 16f

        val rh = 13f  // row height
        val eff = if (st.effectiveTemp > 0f) "%.0fC".format(st.effectiveTemp) else "--"
        dataRow(canvas, y, "T", eff, if (st.heaterMode > 0) hi else lo); y += rh
        dataRow(canvas, y, "H", st.heaterLabel, if (st.heaterMode > 0) hi else lo); y += rh
        dataRow(canvas, y, "B", "${st.batteryPercent}%",
            if (st.batteryPercent > 20) hi else lo); y += rh

        // S only when heating
        y += rh  // blank line
        if (st.heaterMode > 0) {
            val s = bleService?.sessionSeconds ?: 0L
            dataRow(canvas, y, "S", "%02d:%02d".format(s / 60, s % 60), hi); y += rh
        }
        dataRow(canvas, y, "ST", "${bleService?.sessionsToday ?: 0}", lo); y += rh
        dataRow(canvas, y, "TS", "${bleService?.totalSessions ?: 0}", lo)
    }

    // ====================================================================
    // SCANNING / CONNECTING
    // ====================================================================

    private fun drawScan(canvas: Canvas) {
        var y = 12f
        val title = "VENTY"
        canvas.drawText(title, (srcW - tb.measureText(title)) / 2f, y, tb)
        y += 16f

        val status = when (conn) {
            VentyBleService.ConnState.IDLE        -> "READY"
            VentyBleService.ConnState.SCANNING    -> "SCANNING"
            VentyBleService.ConnState.CONNECTING  -> "CONNECT"
            VentyBleService.ConnState.DISCOVERING -> "DISCOVER"
            VentyBleService.ConnState.SUBSCRIBING -> "INIT"
            else -> "..."
        }
        canvas.drawText(status, 6f, y, if (blink) hi else lo)
        y += 16f

        canvas.drawText("Searching for", 6f, y, lo); y += 13f
        canvas.drawText("S&B Venty...", 6f, y, lo)
    }

    // ====================================================================
    // DISCONNECTED
    // ====================================================================

    private fun drawLost(canvas: Canvas) {
        var y = 20f
        val title = "VENTY"
        canvas.drawText(title, (srcW - tb.measureText(title)) / 2f, y, tb)
        y += 16f
        canvas.drawText("not found", 6f, y, lo); y += 13f
        canvas.drawText("TAP to start", 6f, y, hi); y += 13f
        canvas.drawText("or reconnect", 6f, y, lo)
    }

    // ---- Helper ----

    private fun dataRow(canvas: Canvas, y: Float, label: String, value: String, vp: Paint) {
        canvas.drawText(label, 6f, y, lo)
        canvas.drawText(value, 35f, y, vp)
    }
}
