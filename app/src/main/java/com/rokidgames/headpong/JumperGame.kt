package com.rokidgames.headpong

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.abs
import kotlin.random.Random

/**
 * Endless Jumper. Spielfeld bleibt fix; Plattformen werden nach jedem Treffer
 * teleportiert, damit der Player nie auf der gleichen ewig hopfen kann.
 *
 * Steuerung: Touchpad-Swipes (vor/zurück, links/rechts — alle 4 Richtungen
 * werden im Host als links/rechts gemappt). Jeder Swipe gibt einen Impuls;
 * Air-Friction bremst zwischen den Swipes.
 */
internal class JumperGame(
    private val srcW: Int,
    private val srcH: Int,
    private val pxFill: Paint,
    private val pxDim: Paint,
    private val textPaint: Paint
) {
    private val player = Player()
    private val platforms = ArrayList<Platform>(8)

    var score = 0; private set
    var gameOver = false; private set

    fun init() {
        score = 0
        gameOver = false
        player.x = srcW / 2f
        player.y = srcH * 0.5f
        player.vy = -JUMP_VY
        player.vx = 0f
        platforms.clear()
        // Boden-Plattform unter dem Player damit der erste Sprung sicher ist
        platforms.add(Platform(srcW * 0.5f - PLATFORM_W * 0.5f, srcH * 0.7f, PLATFORM_W))
        repeat(4) { platforms.add(randomPlatform()) }
    }

    /** Touchpad: Swipe nach links / rückwärts. */
    fun moveLeft() {
        player.vx = (player.vx - SWIPE_IMPULSE).coerceAtLeast(-MAX_VX)
    }

    /** Touchpad: Swipe nach rechts / vorwärts. */
    fun moveRight() {
        player.vx = (player.vx + SWIPE_IMPULSE).coerceAtMost(MAX_VX)
    }

    fun update(dt: Float) {
        if (gameOver) return

        // Air-Friction: ohne Swipes kommt der Player langsam zum Stillstand
        val friction = (1f - FRICTION_RATE * dt).coerceAtLeast(0f)
        player.vx *= friction

        // Physik
        player.vy += GRAVITY * dt
        player.x  += player.vx * dt
        player.y  += player.vy * dt

        // Horizontal wrappen (Doodle-Jump-Stil)
        if (player.x < -2f) player.x += srcW + 4f
        if (player.x > srcW + 2f) player.x -= srcW + 4f

        // Plattform-Kollision NUR beim Fallen
        if (player.vy > 0f) {
            for (i in platforms.indices) {
                val p = platforms[i]
                val onX = player.x in (p.x - 1f)..(p.x + p.w + 1f)
                val crossing = (player.y in p.y..(p.y + 4f))
                if (onX && crossing) {
                    player.vy = -JUMP_VY
                    player.y = p.y - 0.1f
                    score += 1
                    platforms[i] = randomPlatform()
                    SoundEngine.jump()
                    break
                }
            }
        }

        if (player.y > srcH + 8f) gameOver = true
    }

    fun draw(canvas: Canvas, best: Int) {
        for (p in platforms) {
            canvas.drawRect(p.x, p.y, p.x + p.w, p.y + 2f, pxFill)
            canvas.drawRect(p.x, p.y + 2f, p.x + p.w, p.y + 3f, pxDim)
        }

        val px = player.x; val py = player.y
        canvas.drawRect(px - 1.5f, py - 6f, px + 1.5f, py - 3f, pxFill)         // Kopf
        canvas.drawRect(px - 2f, py - 3f, px + 2f, py + 1f, pxFill)              // Körper
        if (player.vy < 0f) {
            canvas.drawRect(px - 2f, py + 1f, px - 1f, py + 3f, pxFill)
            canvas.drawRect(px + 1f, py + 1f, px + 2f, py + 3f, pxFill)
        } else {
            canvas.drawRect(px - 2f, py + 1f, px - 1f, py + 4f, pxFill)
            canvas.drawRect(px + 1f, py + 1f, px + 2f, py + 4f, pxFill)
        }
        if (abs(player.vx) > 5f) {
            val arm = if (player.vx > 0) 2f else -3f
            canvas.drawRect(px + arm, py - 2f, px + arm + 1f, py + 1f, pxFill)
        }

        canvas.drawText("SCORE %d".format(score), 2f, 8f, textPaint)
        if (best > 0) {
            val s = "BEST %d".format(best)
            val w = textPaint.measureText(s)
            canvas.drawText(s, srcW - w - 2f, 8f, textPaint)
        }
    }

    private fun randomPlatform() = Platform(
        x = Random.nextFloat() * (srcW - PLATFORM_W),
        y = PLATFORM_TOP_Y + Random.nextFloat() * (PLATFORM_BOTTOM_Y - PLATFORM_TOP_Y),
        w = PLATFORM_W
    )

    private class Player { var x = 0f; var y = 0f; var vx = 0f; var vy = 0f }
    private class Platform(val x: Float, val y: Float, val w: Float)

    companion object {
        private const val GRAVITY = 200f
        private const val JUMP_VY = 130f
        private const val MAX_VX  = 90f
        private const val SWIPE_IMPULSE = 50f          // Geschwindigkeits-Boost pro Swipe
        private const val FRICTION_RATE = 1.5f         // 1/s — exponentielles Abklingen
        private const val PLATFORM_W = 16f
        private const val PLATFORM_TOP_Y = 18f
        private const val PLATFORM_BOTTOM_Y = 100f
    }
}
