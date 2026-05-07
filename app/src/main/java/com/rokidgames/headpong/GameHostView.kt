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
import kotlin.math.max

/**
 * Single-View Game Host.
 *
 *   GREETING (2 s) → MENU → JUMPER | SNAKE | ASTEROID | 3D PONG | 3DOF
 *                  → GAME_OVER (2.5 s) → MENU
 *
 * Eingabe-Mapping pro Phase:
 *
 *   im **MENU**     : UP/DOWN = Auswahl, TAP = starten. LEFT/RIGHT = nichts.
 *   im **JUMPER**   : UP = nach rechts, DOWN = nach links. LEFT/RIGHT = nichts.
 *   im **SNAKE**    : LEFT = turn left, RIGHT = turn right. UP/DOWN/TAP = nichts.
 *   im **ASTEROID** : LEFT/RIGHT = Bias-Shift, TAP = re-center.
 *   in **3D PONG / 3DOF** : Kopf-3DOF steuert die Welt, TAP = re-anchor.
 *   global          : BACK (Doppel-Tap) → zurück ins Menü.
 *
 * Render: alle Phases zeichnen in eine 80×120 Pixel-Bitmap, die wird mit
 * **10 % top + 10 % bottom Padding** auf den Display-Canvas geblitted
 * (User-Test: obere/untere Bildränder schwer sichtbar in der Brille).
 */
class GameHostView(ctx: Context) : View(ctx) {

    // ---------- Pixel-Bitmap (Low-Res Render Target) ----------
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
        isAntiAlias = false
        color = GREEN_HI
        textSize = 8f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val textBig = Paint().apply {
        isAntiAlias = false
        color = GREEN_HI
        textSize = 12f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    // ---------- Sensor-Input ----------
    @Volatile var headYaw = 0f
    @Volatile var headPitch = 0f
    /** Komplett-Rotation als 4-float Quaternion vom GAME_ROTATION_VECTOR (für 3DOF). */
    @Volatile var rotationVector: FloatArray? = null

    // ---------- Phase ----------
    private enum class Phase { GREETING, MENU, JUMPER, SNAKE, ASTEROID, THREE_PONG, THREE_DOF, GAME_OVER }
    private var phase = Phase.GREETING
    private var phaseStartMs = SystemClock.elapsedRealtime()
    private var lastFrameNs = 0L

    // ---------- Menu ----------
    private val menuItems = listOf("JUMPER", "SNAKE", "ASTEROID", "3D PONG", "3DOF")
    private var menuIdx = 0

    // ---------- Spiele ----------
    private val jumper    = JumperGame(srcW, srcH, pxFill, pxDim, textPaint)
    private val snake     = SnakeGame(srcW, srcH, pxFill, pxDim, textPaint)
    private val asteroid  = AsteroidGame(srcW, srcH, pxFill, pxDim, textPaint)
    private val threePong = ThreePongGame(srcW, srcH, pxFill, pxDim, textPaint)
    private val threeDof  = ThreeDofGame(srcW, srcH, pxFill, pxDim, textPaint)
    private var bestJumper = 0
    private var bestSnake = 0
    private var bestAsteroid = 0
    private var bestThreePong = 0

    // GAME_OVER context
    private var lastFinishedGame = "JUMPER"
    private var lastScore = 0
    private var lastBest  = 0

    // ---------- Touch-Fallback ----------
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownMs = 0L

    // =====================================================================
    // Public API für MainActivity (KeyEvents → diese Methoden)
    // =====================================================================

    /** UP-Swipe / DPAD_UP. */
    fun onUp() {
        when (phase) {
            Phase.MENU -> { menuIdx = (menuIdx + menuItems.size - 1) % menuItems.size; SoundEngine.click() }
            Phase.JUMPER -> { jumper.moveRight(); SoundEngine.click() }
            else -> Unit
        }
    }

    /** DOWN-Swipe / DPAD_DOWN. */
    fun onDown() {
        when (phase) {
            Phase.MENU -> { menuIdx = (menuIdx + 1) % menuItems.size; SoundEngine.click() }
            Phase.JUMPER -> { jumper.moveLeft(); SoundEngine.click() }
            else -> Unit
        }
    }

    /** LEFT-Swipe / DPAD_LEFT. Im Menu + Jumper bewusst KEINE Aktion. */
    fun onLeft() {
        when (phase) {
            Phase.SNAKE -> { snake.turnLeft(); SoundEngine.click() }
            Phase.ASTEROID -> { asteroid.nudgeRight(); SoundEngine.click() }   // Asteroid invertiert
            else -> Unit
        }
    }

    /** RIGHT-Swipe / DPAD_RIGHT. Im Menu + Jumper bewusst KEINE Aktion. */
    fun onRight() {
        when (phase) {
            Phase.SNAKE -> { snake.turnRight(); SoundEngine.click() }
            Phase.ASTEROID -> { asteroid.nudgeLeft(); SoundEngine.click() }    // Asteroid invertiert
            else -> Unit
        }
    }

    /** TAP / DPAD_CENTER / ENTER. */
    fun onPrimary() {
        when (phase) {
            Phase.MENU       -> { SoundEngine.confirm(); startSelected() }
            Phase.GAME_OVER  -> { SoundEngine.confirm(); goToMenu() }
            Phase.ASTEROID   -> { SoundEngine.blip(); asteroid.recenter() }
            Phase.THREE_PONG -> { SoundEngine.blip(); threePong.recenter() }
            Phase.THREE_DOF  -> { SoundEngine.blip(); threeDof.recenter() }
            Phase.JUMPER, Phase.SNAKE, Phase.GREETING -> Unit
        }
    }

    /** Doppel-Tap / BACK. true = wir haben konsumiert. */
    fun onBack(): Boolean = when (phase) {
        Phase.JUMPER, Phase.SNAKE, Phase.ASTEROID, Phase.THREE_PONG,
        Phase.THREE_DOF, Phase.GAME_OVER -> { goToMenu(); true }
        Phase.MENU, Phase.GREETING -> false
    }

    // =====================================================================
    // Touch-Fallback (für falls Touchpad raw kommt statt KeyEvents)
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
                    if (dx > 0) onRight() else onLeft()
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
        val dt = if (lastFrameNs == 0L) 0f else ((nowNs - lastFrameNs) / 1e9f).coerceAtMost(0.05f)
        lastFrameNs = nowNs

        // Display ringsum schwarz, damit das Padding sauber bleibt zwischen Frames
        canvas.drawPaint(bgPaint)

        pixelCanvas.drawColor(Color.BLACK)

        when (phase) {
            Phase.GREETING -> {
                drawGreeting()
                if (SystemClock.elapsedRealtime() - phaseStartMs >= 2_000L) {
                    goToMenu()       // startet auch die Menu-Music
                }
            }
            Phase.MENU -> drawMenu()
            Phase.JUMPER -> {
                jumper.update(dt)
                jumper.draw(pixelCanvas, bestJumper)
                if (jumper.gameOver) finishGame("JUMPER", jumper.score) {
                    bestJumper = max(bestJumper, jumper.score); bestJumper
                }
            }
            Phase.SNAKE -> {
                snake.update()
                snake.draw(pixelCanvas, bestSnake)
                if (snake.gameOver) finishGame("SNAKE", snake.score) {
                    bestSnake = max(bestSnake, snake.score); bestSnake
                }
            }
            Phase.ASTEROID -> {
                asteroid.update(dt, headYaw, headPitch)
                asteroid.draw(pixelCanvas, bestAsteroid)
                if (asteroid.gameOver) finishGame("ASTEROID", asteroid.score) {
                    bestAsteroid = max(bestAsteroid, asteroid.score); bestAsteroid
                }
            }
            Phase.THREE_PONG -> {
                threePong.update(dt, rotationVector)
                threePong.draw(pixelCanvas, bestThreePong)
                if (threePong.gameOver) finishGame("3D PONG", threePong.score) {
                    bestThreePong = max(bestThreePong, threePong.score); bestThreePong
                }
            }
            Phase.THREE_DOF -> {
                threeDof.update(dt, rotationVector)
                threeDof.draw(pixelCanvas, 0)
            }
            Phase.GAME_OVER -> {
                drawGameOver()
                if (SystemClock.elapsedRealtime() - phaseStartMs > 2_500L) goToMenu()
            }
        }

        // 10 % Sicherheitsabstand oben/unten — Brillenränder schwer sichtbar
        val padY = (height * 0.10f).toInt()
        val dst = Rect(0, padY, width, height - padY)
        canvas.drawBitmap(pixelBmp, srcRect, dst, blitPaint)

        postInvalidateOnAnimation()
    }

    // =====================================================================
    // Phase-Übergänge
    // =====================================================================
    private fun startSelected() {
        when (menuItems[menuIdx]) {
            "JUMPER"   -> { jumper.init();          phase = Phase.JUMPER }
            "SNAKE"    -> { snake.init();           phase = Phase.SNAKE  }
            "ASTEROID" -> { asteroid.init();        phase = Phase.ASTEROID }
            "3D PONG"  -> { threePong.init();       phase = Phase.THREE_PONG }
            "3DOF"     -> { threeDof.init();        phase = Phase.THREE_DOF }
        }
        phaseStartMs = SystemClock.elapsedRealtime()
    }

    private fun goToMenu() {
        phase = Phase.MENU
        phaseStartMs = SystemClock.elapsedRealtime()
    }

    private inline fun finishGame(name: String, score: Int, updateBest: () -> Int) {
        lastFinishedGame = name
        lastScore = score
        lastBest = updateBest()
        phase = Phase.GAME_OVER
        phaseStartMs = SystemClock.elapsedRealtime()
        SoundEngine.gameOver()                           // einmaliger absteigender Sweep
    }

    // =====================================================================
    // Drawing helpers
    // =====================================================================
    private fun drawGreeting() {
        val msg1 = "HALLO"
        val msg2 = "CHRISTOPH"
        val w1 = textBig.measureText(msg1)
        val w2 = textBig.measureText(msg2)
        pixelCanvas.drawText(msg1, (srcW - w1) / 2f, srcH / 2f - 4f, textBig)
        pixelCanvas.drawText(msg2, (srcW - w2) / 2f, srcH / 2f + 10f, textBig)
    }

    private fun drawMenu() {
        val title = "ROKID GAMES"
        val tw = textBig.measureText(title)
        pixelCanvas.drawText(title, (srcW - tw) / 2f, 14f, textBig)

        var y = 32f
        for ((i, item) in menuItems.withIndex()) {
            val prefix = if (i == menuIdx) "> " else "  "
            val text = "$prefix$item"
            val w = textPaint.measureText(text)
            pixelCanvas.drawText(text, (srcW - w) / 2f, y, textPaint)
            y += 11f
        }

        val hint1 = "SWIPE UP/DN"
        val hint2 = "TAP TO START"
        val hw1 = textPaint.measureText(hint1)
        val hw2 = textPaint.measureText(hint2)
        pixelCanvas.drawText(hint1, (srcW - hw1) / 2f, srcH - 18f, textPaint)
        pixelCanvas.drawText(hint2, (srcW - hw2) / 2f, srcH - 6f, textPaint)
    }

    private fun drawGameOver() {
        val msg = "GAME OVER"
        val w = textBig.measureText(msg)
        pixelCanvas.drawText(msg, (srcW - w) / 2f, srcH / 2f - 8f, textBig)

        val sub = "%s  %d".format(lastFinishedGame, lastScore)
        val ws = textPaint.measureText(sub)
        pixelCanvas.drawText(sub, (srcW - ws) / 2f, srcH / 2f + 4f, textPaint)

        if (lastBest > 0) {
            val b = "BEST  %d".format(lastBest)
            val wb = textPaint.measureText(b)
            pixelCanvas.drawText(b, (srcW - wb) / 2f, srcH / 2f + 14f, textPaint)
        }
    }

    companion object {
        private const val GREEN_HI = 0xFF00FF00.toInt()
        private const val GREEN_LO = 0xFF008800.toInt()
    }
}
