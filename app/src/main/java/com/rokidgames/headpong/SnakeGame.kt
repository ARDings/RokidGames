package com.rokidgames.headpong

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.max
import kotlin.random.Random

/**
 * Snake. Steuerung ist *Turn-relativ*:
 *   - prev/links  → 90° gegen den Uhrzeigersinn
 *   - next/rechts → 90° im Uhrzeigersinn
 * passt zum linearen Touchpad am Brillen-Bügel.
 */
internal class SnakeGame(
    private val srcW: Int,
    private val srcH: Int,
    private val pxFill: Paint,
    private val pxDim: Paint,
    private val textPaint: Paint
) {

    enum class Dir(val dx: Int, val dy: Int) {
        UP(0, -1), RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0);
        fun turnLeft()  = entries[(ordinal + 3) % 4]
        fun turnRight() = entries[(ordinal + 1) % 4]
        fun isOpposite(o: Dir) = dx == -o.dx && dy == -o.dy
    }

    // Spielfeld bekommt zusätzlich 5 % Vertical-Padding oben + unten — sonst klebt
    // die Schlange zu nah an den schwer sichtbaren Brillen-Rändern.
    private val playTop    = HUD_H + (srcH * 0.05f).toInt()    // 10 + 6 = 16
    private val playBottom = srcH - (srcH * 0.05f).toInt()      // 120 - 6 = 114
    private val cols = srcW / CELL                              // 16
    private val rows = (playBottom - playTop) / CELL            // 19

    // body: head ist letztes Element, tail erstes
    private val body = ArrayDeque<IntArray>()
    private var dir = Dir.RIGHT
    private var pendingDir = Dir.RIGHT
    private val food = intArrayOf(0, 0)

    var score = 0; private set
    var gameOver = false; private set

    private var lastTickNs = 0L
    private var tickMs = 220L

    fun init() {
        body.clear()
        val cx = cols / 2; val cy = rows / 2
        body.addLast(intArrayOf(cx - 2, cy))
        body.addLast(intArrayOf(cx - 1, cy))
        body.addLast(intArrayOf(cx, cy))
        dir = Dir.RIGHT
        pendingDir = Dir.RIGHT
        score = 0
        gameOver = false
        tickMs = 220L
        spawnFood()
        lastTickNs = System.nanoTime()
    }

    fun turnLeft() {
        val n = dir.turnLeft()
        if (!n.isOpposite(dir)) pendingDir = n
    }

    fun turnRight() {
        val n = dir.turnRight()
        if (!n.isOpposite(dir)) pendingDir = n
    }

    fun update() {
        if (gameOver) return
        val now = System.nanoTime()
        if ((now - lastTickNs) / 1_000_000L < tickMs) return
        lastTickNs = now

        if (!pendingDir.isOpposite(dir)) dir = pendingDir

        val head = body.last()
        val nx = head[0] + dir.dx
        val ny = head[1] + dir.dy

        // Wand
        if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
            gameOver = true
            SoundEngine.crash()
            return
        }

        // Self-Collision (wir prüfen alle Body-Cells außer dem Schwanz, weil der Schwanz
        // sich gleich wegbewegt — außer wir essen, dann bleibt er)
        val ate = nx == food[0] && ny == food[1]
        val skipTail = !ate
        for (i in 0 until body.size) {
            if (skipTail && i == 0) continue
            val c = body[i]
            if (c[0] == nx && c[1] == ny) {
                gameOver = true
                SoundEngine.crash()
                return
            }
        }

        body.addLast(intArrayOf(nx, ny))
        if (ate) {
            score++
            spawnFood()
            tickMs = max(90L, tickMs - 4L)   // immer ein bisschen schneller
            SoundEngine.pickup()
        } else {
            body.removeFirst()
        }
    }

    private fun spawnFood() {
        var tries = 0
        while (tries < 200) {
            val fx = Random.nextInt(cols)
            val fy = Random.nextInt(rows)
            if (body.none { it[0] == fx && it[1] == fy }) {
                food[0] = fx; food[1] = fy
                return
            }
            tries++
        }
    }

    fun draw(canvas: Canvas, best: Int) {
        // HUD
        canvas.drawText("SCORE %d".format(score), 2f, 8f, textPaint)
        if (best > 0) {
            val s = "BEST %d".format(best)
            val w = textPaint.measureText(s)
            canvas.drawText(s, srcW - w - 2f, 8f, textPaint)
        }

        // Spielfeld-Rahmen (1 px ringsum, dim)
        val top = playTop.toFloat()
        val bot = playBottom.toFloat()
        canvas.drawRect(0f, top - 1f, srcW.toFloat(), top, pxDim)
        canvas.drawRect(0f, bot, srcW.toFloat(), bot + 1f, pxDim)
        canvas.drawRect(0f, top, 1f, bot, pxDim)
        canvas.drawRect(srcW - 1f, top, srcW.toFloat(), bot, pxDim)

        // Snake
        for (c in body) {
            val x = c[0] * CELL.toFloat()
            val y = c[1] * CELL.toFloat() + top
            canvas.drawRect(x + 0.5f, y + 0.5f, x + CELL - 0.5f, y + CELL - 0.5f, pxFill)
        }

        // Food blinkt
        val blink = (System.currentTimeMillis() / 200L) % 2L == 0L
        if (blink) {
            val fx = food[0] * CELL.toFloat()
            val fy = food[1] * CELL.toFloat() + top
            canvas.drawRect(fx, fy, fx + CELL, fy + CELL, pxFill)
        }
    }

    companion object {
        private const val CELL = 5
        private const val HUD_H = 10
    }
}
