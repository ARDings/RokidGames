package com.rokidgames.headpong

import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.SensorManager

/**
 * 3DOF-Demo — Würfel bleibt im Raum stehen während der Kopf sich dreht.
 *
 * Wie es funktioniert (kein Fake — das IST 3DOF):
 *   - Beim Start merken wir die aktuelle Geräte-zu-Welt-Rotation als "Anker".
 *     Diese Rotationsmatrix beschreibt: "wie war der Kopf orientiert,
 *     als der Würfel platziert wurde".
 *   - Würfel wird in *Anker-Koordinaten* definiert (3 Einheiten vor uns).
 *   - Jeden Frame: aktuelle Rotation R_now lesen,
 *     relative Drehung berechnen: R_rel = R_now^T · R_anchor.
 *   - Würfel-Eckpunkte mit R_rel transformieren → Punkte im aktuellen Sichtfeld.
 *   - Perspektivische Projektion → 2D-Pixel.
 *
 * Daraus folgt: dreht der Spieler den Kopf nach links, dreht sich R_rel mit,
 * der Würfel "wandert" im Bild nach rechts und scheint im Raum stehenzubleiben.
 *
 * Limitierung: Yaw driftet (kein Magnetometer in der 6-axis IMU der Brille).
 * Tap = re-anchor → Würfel wieder mittig.
 */
internal class ThreeDofGame(
    private val srcW: Int,
    private val srcH: Int,
    private val pxFill: Paint,
    private val pxDim: Paint,
    private val textPaint: Paint
) {

    // ---- Projection ----
    private val cx = srcW / 2f
    private val cy = srcH / 2f
    private val focal = (srcH / 2f) / 0.6f       // FOV ≈ 30°-äquivalent für unsere Brille

    // ---- Rotation matrices ----
    private val anchorR  = FloatArray(9)
    private val currentR = FloatArray(9)
    private val relR     = FloatArray(9)
    private var hasAnchor = false

    // ---- Cube definition (Anker-Frame) ----
    private val cubeCenterZ = -3.0f                     // 3 Einheiten "vor" uns
    private val cubeHalf    = 0.55f
    private val cubeWorld = Array(8) { i ->
        floatArrayOf(
            if (i and 1 != 0) cubeHalf else -cubeHalf,
            if (i and 2 != 0) cubeHalf else -cubeHalf,
            cubeCenterZ + (if (i and 4 != 0) cubeHalf else -cubeHalf)
        )
    }
    private val cubeEdges = arrayOf(
        // hintere Fläche (nahe z = cubeCenterZ - cubeHalf)
        intArrayOf(0, 1), intArrayOf(1, 3), intArrayOf(3, 2), intArrayOf(2, 0),
        // vordere Fläche
        intArrayOf(4, 5), intArrayOf(5, 7), intArrayOf(7, 6), intArrayOf(6, 4),
        // Verbindungen
        intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7)
    )

    // Eine kleine "Welt-Achse" die zeigt wo "vorne in der Welt" ist
    private val axisWorld = arrayOf(
        floatArrayOf(0f, 0f, 0f),       // origin
        floatArrayOf(0.4f, 0f, 0f),     // +X
        floatArrayOf(0f, 0.4f, 0f),     // +Y
        floatArrayOf(0f, 0f, -0.4f)     // +Z (vorne)
    )

    // Output buffers
    private val viewVec     = Array(8) { FloatArray(3) }
    private val viewAxisVec = Array(4) { FloatArray(3) }
    private val proj        = Array(8) { FloatArray(3) }    // [x, y, valid]
    private val projAxis    = Array(4) { FloatArray(3) }

    // Always false — endless demo, exit per Doppel-Tap
    var gameOver = false; private set
    var score = 0; private set

    fun init() {
        hasAnchor = false
        gameOver = false
    }

    /** Touchpad TAP — neuen Anker setzen, Würfel wieder vor uns platzieren. */
    fun recenter() {
        hasAnchor = false
    }

    fun update(@Suppress("UNUSED_PARAMETER") dt: Float, rotVec: FloatArray?) {
        if (rotVec == null) return
        if (!hasAnchor) {
            SensorManager.getRotationMatrixFromVector(anchorR, rotVec)
            hasAnchor = true
        }
        SensorManager.getRotationMatrixFromVector(currentR, rotVec)
        // R_rel = R_now^T · R_anchor
        multiplyTransposedFirst(currentR, anchorR, relR)

        for (i in 0..7) transform(cubeWorld[i], viewVec[i])
        for (i in 0..3) transform(axisWorld[i], viewAxisVec[i])

        for (i in 0..7) project(viewVec[i], proj[i])
        for (i in 0..3) project(viewAxisVec[i], projAxis[i])
    }

    fun draw(canvas: Canvas, @Suppress("UNUSED_PARAMETER") best: Int) {
        if (!hasAnchor) {
            val msg = "WAIT FOR IMU…"
            val w = textPaint.measureText(msg)
            canvas.drawText(msg, (srcW - w) / 2f, srcH / 2f, textPaint)
            return
        }

        // Welt-Achsen-Helper (dimmer als Würfel)
        val origin = projAxis[0]
        if (origin[2] != 0f) {
            for (i in 1..3) {
                val tip = projAxis[i]
                if (tip[2] != 0f) {
                    canvas.drawLine(origin[0], origin[1], tip[0], tip[1], pxDim)
                }
            }
        }

        // Würfel-Kanten
        for (edge in cubeEdges) {
            val a = proj[edge[0]]; val b = proj[edge[1]]
            if (a[2] == 0f || b[2] == 0f) continue
            canvas.drawLine(a[0], a[1], b[0], b[1], pxFill)
        }

        // HUD
        canvas.drawText("3DOF DEMO", 2f, 8f, textPaint)
        val hint = "TAP = RECENTER"
        val hw = textPaint.measureText(hint)
        canvas.drawText(hint, (srcW - hw) / 2f, srcH - 4f, textPaint)
    }

    // ---- helpers ----

    private fun transform(src: FloatArray, dst: FloatArray) {
        val x = src[0]; val y = src[1]; val z = src[2]
        dst[0] = relR[0] * x + relR[1] * y + relR[2] * z
        dst[1] = relR[3] * x + relR[4] * y + relR[5] * z
        dst[2] = relR[6] * x + relR[7] * y + relR[8] * z
    }

    private fun project(src: FloatArray, dst: FloatArray) {
        val z = src[2]
        if (z >= -0.1f) { dst[2] = 0f; return }                 // hinter der Kamera
        dst[0] = cx + (src[0] / -z) * focal
        dst[1] = cy - (src[1] / -z) * focal
        dst[2] = 1f
    }

    /** out = a^T · b   (alle 3×3 row-major) */
    private fun multiplyTransposedFirst(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (i in 0..2) for (j in 0..2) {
            var s = 0f
            for (k in 0..2) s += a[k * 3 + i] * b[k * 3 + j]
            out[i * 3 + j] = s
        }
    }
}
