package com.rokidgames.headpong

import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.random.Random

/**
 * 3D Pong mit echtem 3DOF-Welt-Anker.
 *
 *   - Spielfeld (Quader) ist im Anker-Frame fixiert → bleibt im Raum stehen,
 *     wenn der Spieler den Kopf dreht (gleiche Mechanik wie ThreeDofGame).
 *   - Ball fliegt in Welt-Koordinaten zwischen den Wänden hin und her.
 *   - Spieler-Paddle ist *view-fixed*: hängt fest in der Bildschirmmitte.
 *   - Gegner-Paddle ist *welt-fixed* an der hinteren Box-Ebene; AI lerpt
 *     zur Ball-XY mit gecappter Speed → trifft meistens, gelegentlich nicht.
 *
 * Spielerische Konsequenz:
 *   - Ball erreicht vordere Wand → Spieler-Paddle muss treffen, sonst -1 Leben
 *   - Ball erreicht hintere Wand → Gegner trifft (Standard) → reflektiert zurück
 *                                  oder Gegner verfehlt → Spieler bekommt Bonus
 *
 * Tap = Re-Anchor (Drift-Korrektur, gleiches Konzept wie 3DOF-Demo).
 */
internal class ThreePongGame(
    private val srcW: Int,
    private val srcH: Int,
    private val pxFill: Paint,
    private val pxDim: Paint,
    private val textPaint: Paint
) {

    // ---- Projection (gleiche FOV wie 3DOF-Demo) ----
    private val cx = srcW / 2f
    private val cy = srcH / 2f
    private val focal = (srcH / 2f) / 0.6f

    // ---- Rotation matrices ----
    private val anchorR  = FloatArray(9)
    private val currentR = FloatArray(9)
    private val relR     = FloatArray(9)
    private var hasAnchor = false

    // ---- Welt-Box (Anker-Frame). z negativ = vor uns. ----
    private val boxHalfW = 1.2f
    private val boxHalfH = 0.9f
    private val boxFrontZ = -0.6f          // vordere Ebene = Player-Ebene
    private val boxBackZ  = -5.0f          // hintere Ebene
    private val boxCornersWorld = arrayOf(
        floatArrayOf(-boxHalfW, -boxHalfH, boxBackZ),    // 0: hinten BL
        floatArrayOf( boxHalfW, -boxHalfH, boxBackZ),    // 1: hinten BR
        floatArrayOf( boxHalfW,  boxHalfH, boxBackZ),    // 2: hinten TR
        floatArrayOf(-boxHalfW,  boxHalfH, boxBackZ),    // 3: hinten TL
        floatArrayOf(-boxHalfW, -boxHalfH, boxFrontZ),   // 4: vorne BL
        floatArrayOf( boxHalfW, -boxHalfH, boxFrontZ),   // 5: vorne BR
        floatArrayOf( boxHalfW,  boxHalfH, boxFrontZ),   // 6: vorne TR
        floatArrayOf(-boxHalfW,  boxHalfH, boxFrontZ)    // 7: vorne TL
    )
    private val boxEdges = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0),
        intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 4),
        intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7)
    )
    // Tiefenraster zwischen vorderer und hinterer Ebene — gibt mehr 3D-Gefühl
    private val depthRingZs = floatArrayOf(-1.5f, -2.5f, -3.5f, -4.2f)

    // ---- Ball ----
    private var ballX = 0f
    private var ballY = 0f
    private var ballZ = 0f
    private var ballVx = 0f
    private var ballVy = 0f
    private var ballVz = 0f
    private val ballR = 0.12f

    // ---- Spieler-Paddle (screen-fixed) ----
    private val paddleHalfW = 12f      // px
    private val paddleHalfH = 16f      // px

    // ---- Gegner-Paddle (welt-fixed an der Hinterwand) ----
    private val enemyHalfW = 0.40f                  // Welt-Einheiten
    private val enemyHalfH = 0.50f
    private val enemySpeed = 0.95f                  // Welt-Einheiten / Sekunde
    private var enemyX = 0f
    private var enemyY = 0f

    var score = 0; private set
    var lives = 3; private set
    var gameOver = false; private set

    private val cornerProj = Array(8) { FloatArray(3) }   // [sx, sy, valid]

    fun init() {
        score = 0
        lives = 3
        gameOver = false
        hasAnchor = false
        enemyX = 0f
        enemyY = 0f
        spawnBall()
    }

    /** Touchpad TAP — Anker neu setzen (Drift-Korrektur). */
    fun recenter() {
        hasAnchor = false
    }

    private fun spawnBall() {
        ballX = (Random.nextFloat() * 2f - 1f) * boxHalfW * 0.4f
        ballY = (Random.nextFloat() * 2f - 1f) * boxHalfH * 0.4f
        ballZ = boxBackZ + 0.4f
        ballVx = (Random.nextFloat() * 2f - 1f) * 0.4f
        ballVy = (Random.nextFloat() * 2f - 1f) * 0.4f
        // Vorwärts (Richtung Spieler) — Ball wird mit jedem Treffer schneller
        ballVz = 1.4f + score * 0.08f
    }

    fun update(dt: Float, rotVec: FloatArray?) {
        if (gameOver || rotVec == null) return
        if (!hasAnchor) {
            SensorManager.getRotationMatrixFromVector(anchorR, rotVec)
            hasAnchor = true
        }
        SensorManager.getRotationMatrixFromVector(currentR, rotVec)
        // R_rel = R_now^T · R_anchor → transformiert vom Anker-Frame in den aktuellen View-Frame
        multiplyTransposedFirst(currentR, anchorR, relR)

        // Gegner-AI: lerpe zur Ball-XY, gecappt durch enemySpeed
        val maxStep = enemySpeed * dt
        val edx = ballX - enemyX
        val edy = ballY - enemyY
        enemyX += signf(edx) * minOf(abs(edx), maxStep)
        enemyY += signf(edy) * minOf(abs(edy), maxStep)
        enemyX = enemyX.coerceIn(-boxHalfW + enemyHalfW, boxHalfW - enemyHalfW)
        enemyY = enemyY.coerceIn(-boxHalfH + enemyHalfH, boxHalfH - enemyHalfH)

        // Ball-Physik in Welt-Koordinaten
        ballX += ballVx * dt
        ballY += ballVy * dt
        ballZ += ballVz * dt

        // Seiten-/Decken-/Boden-Reflexionen
        if (ballX < -boxHalfW + ballR) { ballX = -boxHalfW + ballR; ballVx = -ballVx }
        if (ballX >  boxHalfW - ballR) { ballX =  boxHalfW - ballR; ballVx = -ballVx }
        if (ballY < -boxHalfH + ballR) { ballY = -boxHalfH + ballR; ballVy = -ballVy }
        if (ballY >  boxHalfH - ballR) { ballY =  boxHalfH - ballR; ballVy = -ballVy }

        // Hinterwand erreicht → Gegner-Paddle-Check
        if (ballVz < 0f && ballZ <= boxBackZ + ballR) {
            val hitX = ballX - enemyX
            val hitY = ballY - enemyY
            val hit = abs(hitX) < enemyHalfW + ballR && abs(hitY) < enemyHalfH + ballR
            if (hit) {
                ballVz = -ballVz
                ballZ = boxBackZ + ballR + 0.05f
                // Spin von der Treffposition relativ zum Gegner-Paddle
                ballVx += (hitX / enemyHalfW) * 0.4f
                ballVy += (hitY / enemyHalfH) * 0.4f
                ballVx = ballVx.coerceIn(-1.5f, 1.5f)
                ballVy = ballVy.coerceIn(-1.5f, 1.5f)
                SoundEngine.pongHit()
            } else {
                // Gegner verfehlt → Spieler-Bonus
                score += 3
                SoundEngine.pickup()
                spawnBall()
            }
        }

        // Vordere Wand erreicht → Spieler-Paddle-Check
        if (ballVz > 0f && ballZ >= boxFrontZ - ballR) {
            val viewVec = transformWorldToView(ballX, ballY, ballZ)
            if (viewVec[2] < -0.05f) {
                val sx = focal * viewVec[0] / -viewVec[2]
                val sy = -focal * viewVec[1] / -viewVec[2]
                val hit = abs(sx) < paddleHalfW + 1f && abs(sy) < paddleHalfH + 1f
                if (hit) {
                    score += 1
                    ballVz = -ballVz
                    ballZ = boxFrontZ - ballR - 0.05f
                    // Spin: relative Treffposition gibt Querimpuls
                    ballVx += (sx / paddleHalfW) * 0.5f
                    ballVy -= (sy / paddleHalfH) * 0.4f
                    // Speed-Cap, sonst entgleitet's
                    ballVx = ballVx.coerceIn(-1.5f, 1.5f)
                    ballVy = ballVy.coerceIn(-1.5f, 1.5f)
                    ballVz = ballVz.coerceAtLeast(-3.0f)
                    SoundEngine.pongHit()
                } else {
                    onMiss()
                }
            } else {
                // Spieler hat sich vom Spielfeld abgewandt
                onMiss()
            }
        }
    }

    private fun onMiss() {
        lives -= 1
        SoundEngine.pongMiss()
        if (lives <= 0) gameOver = true
        else spawnBall()
    }

    fun draw(canvas: Canvas, best: Int) {
        if (!hasAnchor) {
            val msg = "WAIT FOR IMU…"
            val w = textPaint.measureText(msg)
            canvas.drawText(msg, (srcW - w) / 2f, srcH / 2f, textPaint)
            return
        }

        // 1) Welt-Box (wireframe, dim)
        drawBox(canvas)
        // 2) Tiefen-Ringe für 3D-Gefühl
        drawDepthRings(canvas)
        // 3) Gegner-Paddle (welt-fixed, hinten — wird von Box "verdeckt" wenn man wegguckt)
        drawEnemyPaddle(canvas)
        // 4) Ball
        drawBall(canvas)
        // 5) Spieler-Paddle — view-fixed (immer in der Bildschirmmitte)
        drawPaddle(canvas)

        // HUD
        canvas.drawText("SCORE %d".format(score), 2f, 8f, textPaint)
        val livesStr = "LIVES %d".format(lives)
        val lw = textPaint.measureText(livesStr)
        canvas.drawText(livesStr, srcW - lw - 2f, 8f, textPaint)
        if (best > 0) {
            val bs = "BEST %d".format(best)
            val bw = textPaint.measureText(bs)
            canvas.drawText(bs, (srcW - bw) / 2f, srcH - 4f, textPaint)
        }
    }

    // -------------- Drawing helpers --------------

    private fun drawBox(canvas: Canvas) {
        for (i in 0..7) {
            val v = transformWorldToView(boxCornersWorld[i][0], boxCornersWorld[i][1], boxCornersWorld[i][2])
            project(v, cornerProj[i])
        }
        for (e in boxEdges) {
            val a = cornerProj[e[0]]; val b = cornerProj[e[1]]
            if (a[2] == 0f || b[2] == 0f) continue
            canvas.drawLine(a[0], a[1], b[0], b[1], pxDim)
        }
    }

    private fun drawDepthRings(canvas: Canvas) {
        val tmp = FloatArray(3)
        val ringCorners = Array(4) { FloatArray(3) }
        for (z in depthRingZs) {
            project(transformWorldToView(-boxHalfW, -boxHalfH, z), ringCorners[0])
            project(transformWorldToView( boxHalfW, -boxHalfH, z), ringCorners[1])
            project(transformWorldToView( boxHalfW,  boxHalfH, z), ringCorners[2])
            project(transformWorldToView(-boxHalfW,  boxHalfH, z), ringCorners[3])
            for (i in 0..3) {
                val a = ringCorners[i]; val b = ringCorners[(i + 1) % 4]
                if (a[2] == 0f || b[2] == 0f) continue
                canvas.drawLine(a[0], a[1], b[0], b[1], pxDim)
            }
        }
    }

    private fun drawBall(canvas: Canvas) {
        val v = transformWorldToView(ballX, ballY, ballZ)
        if (v[2] >= -0.05f) return
        val s = focal / -v[2]
        val sx = cx + v[0] * s
        val sy = cy - v[1] * s
        val r = (ballR * s).coerceAtLeast(1f)
        canvas.drawRect(sx - r, sy - r, sx + r, sy + r, pxFill)
    }

    private fun drawPaddle(canvas: Canvas) {
        // Wireframe-Rechteck in der Bildschirmmitte
        canvas.drawRect(cx - paddleHalfW, cy - paddleHalfH, cx + paddleHalfW, cy - paddleHalfH + 1f, pxFill)
        canvas.drawRect(cx - paddleHalfW, cy + paddleHalfH - 1f, cx + paddleHalfW, cy + paddleHalfH, pxFill)
        canvas.drawRect(cx - paddleHalfW, cy - paddleHalfH, cx - paddleHalfW + 1f, cy + paddleHalfH, pxFill)
        canvas.drawRect(cx + paddleHalfW - 1f, cy - paddleHalfH, cx + paddleHalfW, cy + paddleHalfH, pxFill)
        // Crosshair in der Mitte
        canvas.drawRect(cx - 3f, cy, cx - 1f, cy + 1f, pxDim)
        canvas.drawRect(cx + 1f, cy, cx + 3f, cy + 1f, pxDim)
        canvas.drawRect(cx, cy - 3f, cx + 1f, cy - 1f, pxDim)
        canvas.drawRect(cx, cy + 1f, cx + 1f, cy + 3f, pxDim)
    }

    private val enemyProj = Array(4) { FloatArray(3) }
    private val enemyCenterProj = FloatArray(3)
    private fun drawEnemyPaddle(canvas: Canvas) {
        // 4 Eckpunkte im Welt-Frame an der Hinterwand
        project(transformWorldToView(enemyX - enemyHalfW, enemyY - enemyHalfH, boxBackZ), enemyProj[0])
        project(transformWorldToView(enemyX + enemyHalfW, enemyY - enemyHalfH, boxBackZ), enemyProj[1])
        project(transformWorldToView(enemyX + enemyHalfW, enemyY + enemyHalfH, boxBackZ), enemyProj[2])
        project(transformWorldToView(enemyX - enemyHalfW, enemyY + enemyHalfH, boxBackZ), enemyProj[3])
        for (i in 0..3) {
            val a = enemyProj[i]; val b = enemyProj[(i + 1) % 4]
            if (a[2] == 0f || b[2] == 0f) continue
            canvas.drawLine(a[0], a[1], b[0], b[1], pxFill)
        }
        // X-Marker in der Mitte → unterscheidet Gegner-Paddle visuell vom Spieler-Crosshair
        project(transformWorldToView(enemyX, enemyY, boxBackZ), enemyCenterProj)
        if (enemyCenterProj[2] != 0f) {
            val mx = enemyCenterProj[0]; val my = enemyCenterProj[1]
            canvas.drawLine(mx - 2f, my - 2f, mx + 2f, my + 2f, pxFill)
            canvas.drawLine(mx - 2f, my + 2f, mx + 2f, my - 2f, pxFill)
        }
    }

    private fun signf(v: Float) = if (v > 0f) 1f else if (v < 0f) -1f else 0f

    // -------------- Math helpers --------------

    private val tmpView = FloatArray(3)
    private fun transformWorldToView(x: Float, y: Float, z: Float): FloatArray {
        tmpView[0] = relR[0] * x + relR[1] * y + relR[2] * z
        tmpView[1] = relR[3] * x + relR[4] * y + relR[5] * z
        tmpView[2] = relR[6] * x + relR[7] * y + relR[8] * z
        return tmpView
    }

    private fun project(view: FloatArray, out: FloatArray) {
        val z = view[2]
        if (z >= -0.05f) { out[2] = 0f; return }
        out[0] = cx + (view[0] / -z) * focal
        out[1] = cy - (view[1] / -z) * focal
        out[2] = 1f
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
