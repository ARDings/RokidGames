package com.rokidgames.headpong

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 3D-Asteroid-Dodger im Wireframe-Tunnel.
 *
 * Steuerung:
 *   - Kopf-Yaw   → Schiff X (kontinuierlich)
 *   - Kopf-Pitch → Schiff Y
 *   - Touchpad TAP    → Kopf-Origin neu kalibrieren (re-center) + Bias zurücksetzen
 *   - Touchpad SWIPE  → Bias-Shift (für falls die Sitzposition gedriftet ist
 *                       und der Spieler die Mitte verschieben will)
 *
 * Render: low-res 80×120 Pixel-Bitmap, Pinhole-Projektion mit Fluchtpunkt
 * in Bildmitte. Asteroiden = wireframe Quadrate (skalieren mit der Tiefe).
 */
internal class AsteroidGame(
    private val srcW: Int,
    private val srcH: Int,
    private val pxFill: Paint,
    private val pxDim: Paint,
    private val textPaint: Paint
) {
    // ---- Pinhole-Projektion ----
    private val cx = srcW / 2f
    private val cy = srcH / 2f
    private val halfW = 1.0f                          // Tunnel-Halbbreite (Welt)
    private val halfH = (srcH.toFloat() / srcW) * halfW   // aspect-richtig
    private val focal = cx / halfW                    // → Welt-Halbbreite füllt Bildbreite bei z=0
    private val eyeZ = 1.0f
    private val zFar = 8f

    // ---- Ship state ----
    private var shipX = 0f
    private var shipY = 0f
    private var yawOrigin = Float.NaN
    private var pitchOrigin = Float.NaN
    private var biasX = 0f                            // Touchpad-Swipe verschiebt diesen Offset
    private var biasY = 0f

    // ---- Asteroids ----
    private val asteroids = ArrayList<Ast>(32)
    private var spawnTimer = 0f

    var score = 0; private set
    var gameOver = false; private set

    private val tmp = FloatArray(3)

    fun init() {
        shipX = 0f; shipY = 0f
        yawOrigin = Float.NaN
        pitchOrigin = Float.NaN
        biasX = 0f; biasY = 0f
        asteroids.clear()
        spawnTimer = 0f
        score = 0
        gameOver = false
    }

    /** Touchpad TAP — Kopfposition als neue Mitte übernehmen. */
    fun recenter() {
        yawOrigin = Float.NaN
        pitchOrigin = Float.NaN
        biasX = 0f; biasY = 0f
    }

    /** Touchpad SWIPE links — Bias um einen Step nach links. */
    fun nudgeLeft()  { biasX = max(biasX - BIAS_STEP, -halfW * 0.6f) }
    /** Touchpad SWIPE rechts — Bias nach rechts. */
    fun nudgeRight() { biasX = min(biasX + BIAS_STEP,  halfW * 0.6f) }

    fun update(dt: Float, yaw: Float, pitch: Float) {
        if (gameOver) return
        if (yawOrigin.isNaN()) { yawOrigin = yaw; pitchOrigin = pitch }

        val dyaw   = yaw - yawOrigin
        val dpitch = pitch - pitchOrigin

        // Kopf links = Schiff nach links → Vorzeichen ggf. tauschen falls falsch wirkt
        val targetX = (-dyaw   / YAW_RANGE_RAD).coerceIn(-1f, 1f) * halfW * 0.85f + biasX
        val targetY = ( dpitch / PITCH_RANGE_RAD).coerceIn(-1f, 1f) * halfH * 0.85f + biasY
        shipX += (targetX - shipX) * min(1f, dt * 10f)
        shipY += (targetY - shipY) * min(1f, dt * 10f)

        // Asteroid spawnen — Frequenz steigt mit dem Score
        spawnTimer += dt
        val interval = max(0.35f, 1.2f - score * 0.006f)
        if (spawnTimer > interval) {
            spawnTimer = 0f
            asteroids.add(Ast(
                x = (Random.nextFloat() * 2f - 1f) * halfW * 0.85f,
                y = (Random.nextFloat() * 2f - 1f) * halfH * 0.85f,
                z = zFar,
                r = 0.10f + Random.nextFloat() * 0.10f
            ))
        }

        // Asteroiden bewegen + Kollision prüfen
        val speed = 4f + score * 0.03f
        val it = asteroids.iterator()
        while (it.hasNext()) {
            val a = it.next()
            a.z -= speed * dt
            // Wenn Asteroid die Spieler-Ebene durchquert, prüfe XY-Distanz
            if (a.z in -0.3f..0.7f) {
                val dx = a.x - shipX
                val dy = a.y - shipY
                val rTotal = a.r + SHIP_RADIUS
                if (dx * dx + dy * dy < rTotal * rTotal) {
                    gameOver = true
                    SoundEngine.crash()
                    return
                }
            }
            if (a.z < -0.5f) {
                it.remove()
                score++
                SoundEngine.ping()
            }
        }
    }

    fun draw(canvas: Canvas, best: Int) {
        drawTunnel(canvas)

        // Asteroiden hinten zuerst rendern (Painter's Algorithm)
        asteroids.sortByDescending { it.z }
        for (a in asteroids) {
            project(a.x, a.y, a.z, tmp)
            drawAsteroid(canvas, tmp[0], tmp[1], a.r * tmp[2])
        }

        // Schiff (Sprite) an aktueller Spieler-Pos
        project(shipX, shipY, 0f, tmp)
        drawShip(canvas, tmp[0], tmp[1])
        // Crosshair drum herum — zeigt wo der Kopf hinguckt
        drawCrosshair(canvas, tmp[0], tmp[1])

        // HUD
        canvas.drawText("SCORE %d".format(score), 2f, 8f, textPaint)
        if (best > 0) {
            val s = "BEST %d".format(best)
            val w = textPaint.measureText(s)
            canvas.drawText(s, srcW - w - 2f, 8f, textPaint)
        }
    }

    // ---- Hilfsfunktionen ----

    private fun project(x: Float, y: Float, z: Float, out: FloatArray) {
        val s = focal / (z + eyeZ)
        out[0] = cx + x * s
        out[1] = cy + y * s
        out[2] = s
    }

    private fun drawTunnel(canvas: Canvas) {
        val zSteps = floatArrayOf(0f, 1.2f, 2.5f, 4f, 6f, 8f)
        val prev = Array(4) { FloatArray(2) }
        val curr = Array(4) { FloatArray(2) }
        for ((idx, z) in zSteps.withIndex()) {
            computeFrame(z, curr)
            // Frame-Rechteck
            canvas.drawLine(curr[0][0], curr[0][1], curr[1][0], curr[1][1], pxDim)
            canvas.drawLine(curr[1][0], curr[1][1], curr[2][0], curr[2][1], pxDim)
            canvas.drawLine(curr[2][0], curr[2][1], curr[3][0], curr[3][1], pxDim)
            canvas.drawLine(curr[3][0], curr[3][1], curr[0][0], curr[0][1], pxDim)
            // Verbindungslinien zur vorigen Tiefenebene
            if (idx > 0) for (i in 0..3) {
                canvas.drawLine(prev[i][0], prev[i][1], curr[i][0], curr[i][1], pxDim)
            }
            for (i in 0..3) { prev[i][0] = curr[i][0]; prev[i][1] = curr[i][1] }
        }
    }

    private fun computeFrame(z: Float, out: Array<FloatArray>) {
        project(-halfW, -halfH, z, tmp); out[0][0] = tmp[0]; out[0][1] = tmp[1]
        project( halfW, -halfH, z, tmp); out[1][0] = tmp[0]; out[1][1] = tmp[1]
        project( halfW,  halfH, z, tmp); out[2][0] = tmp[0]; out[2][1] = tmp[1]
        project(-halfW,  halfH, z, tmp); out[3][0] = tmp[0]; out[3][1] = tmp[1]
    }

    private fun drawAsteroid(canvas: Canvas, sx: Float, sy: Float, r: Float) {
        if (r < 1.2f) {
            // Weit weg = nur ein Punkt
            canvas.drawRect(sx, sy, sx + 1f, sy + 1f, pxFill)
            return
        }
        // Wireframe-Quadrat
        canvas.drawRect(sx - r, sy - r, sx + r,       sy - r + 1f, pxFill) // oben
        canvas.drawRect(sx - r, sy + r - 1f, sx + r,  sy + r,      pxFill) // unten
        canvas.drawRect(sx - r, sy - r, sx - r + 1f,  sy + r,      pxFill) // links
        canvas.drawRect(sx + r - 1f, sy - r, sx + r,  sy + r,      pxFill) // rechts
    }

    private fun drawShip(canvas: Canvas, sx: Float, sy: Float) {
        // Pixel-Raumschiff von vorn (Cockpit-Sicht): kleines Plus mit Flügel-Tips
        canvas.drawRect(sx - 1f, sy - 3f, sx + 1f, sy - 1f, pxFill)  // Spitze
        canvas.drawRect(sx - 3f, sy - 1f, sx + 3f, sy + 1f, pxFill)  // Rumpf
        canvas.drawRect(sx - 4f, sy + 1f, sx - 2f, sy + 3f, pxFill)  // linker Triebwerk
        canvas.drawRect(sx + 2f, sy + 1f, sx + 4f, sy + 3f, pxFill)  // rechter Triebwerk
    }

    private fun drawCrosshair(canvas: Canvas, sx: Float, sy: Float) {
        val len = 5f; val gap = 3f
        canvas.drawRect(sx - len, sy,        sx - gap, sy + 1f, pxDim)
        canvas.drawRect(sx + gap, sy,        sx + len, sy + 1f, pxDim)
        canvas.drawRect(sx,       sy - len,  sx + 1f,  sy - gap, pxDim)
        canvas.drawRect(sx,       sy + gap,  sx + 1f,  sy + len, pxDim)
    }

    private class Ast(var x: Float, var y: Float, var z: Float, val r: Float)

    companion object {
        private val YAW_RANGE_RAD   = (25.0 * PI / 180.0).toFloat()
        private val PITCH_RANGE_RAD = (18.0 * PI / 180.0).toFloat()
        private const val SHIP_RADIUS = 0.18f
        private const val BIAS_STEP   = 0.12f
    }
}
