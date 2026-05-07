package com.rokidgames.headpong

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Minimaler PCM-Synth — nur Sound-Effekte.
 *   - Erzeugt 16-bit Mono Samples on-the-fly (Sin-Tones, lineare Sweeps, weißes Rauschen).
 *   - Effekte werden über einen single-thread Executor abgespielt (queued,
 *     fire-and-forget). Reicht für sporadische Game-Events.
 *
 * Bewusst keine Asset-Files — alles generiert. APK bleibt minimal.
 */
object SoundEngine {

    private const val SR = 22_050
    private val effectExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HeadPong/Sfx").apply { isDaemon = true }
    }

    // =====================================================================
    // Public effect API — kurze prägnante Calls
    // =====================================================================

    fun click()     = tone(900f, 30, 0.20f)              // Menu nav
    fun blip()      = tone(440f, 50, 0.25f)              // generic UI
    fun confirm()   = sweep(440f, 880f, 110, 0.30f)      // Tap=Start
    fun jump()      = sweep(220f, 440f, 90, 0.28f)       // Jumper plat-hit
    fun pickup()    = sweep(660f, 990f, 110, 0.30f)      // Snake eat
    fun ping()      = tone(330f, 50, 0.18f)              // Asteroid passed
    fun pongHit()   = tone(880f, 60, 0.30f)              // 3D Pong paddle
    fun pongMiss()  = tone(165f, 200, 0.25f)             // 3D Pong miss
    fun crash()     = noise(180, 0.35f)                  // collision
    fun gameOver()  = sweep(440f, 110f, 600, 0.30f)      // descending

    // Generic API für custom calls
    fun tone(freq: Float, durMs: Int, vol: Float = 0.30f) {
        effectExec.submitSafe { play(genTone(freq, durMs, vol)) }
    }
    fun sweep(f1: Float, f2: Float, durMs: Int, vol: Float = 0.30f) {
        effectExec.submitSafe { play(genSweep(f1, f2, durMs, vol)) }
    }
    fun noise(durMs: Int, vol: Float = 0.30f) {
        effectExec.submitSafe { play(genNoise(durMs, vol)) }
    }

    fun shutdown() {
        effectExec.shutdownNow()
    }

    // =====================================================================
    // Sample-Generatoren
    // =====================================================================

    private fun genTone(freq: Float, durMs: Int, vol: Float): ShortArray {
        val n = SR * durMs / 1000
        val buf = ShortArray(n)
        for (i in 0 until n) {
            val s = sin(2 * PI * freq * i / SR).toFloat() * env(i, n) * vol
            buf[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return buf
    }

    private fun genSweep(f1: Float, f2: Float, durMs: Int, vol: Float): ShortArray {
        val n = SR * durMs / 1000
        val buf = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val u = i.toFloat() / n
            val f = f1 + (f2 - f1) * u
            phase += 2 * PI * f / SR
            val s = sin(phase).toFloat() * env(i, n) * vol
            buf[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return buf
    }

    private fun genNoise(durMs: Int, vol: Float): ShortArray {
        val n = SR * durMs / 1000
        val buf = ShortArray(n)
        for (i in 0 until n) {
            val s = (Random.nextFloat() * 2f - 1f) * env(i, n) * vol
            buf[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return buf
    }

    /** Attack/Release-Hüllkurve gegen Klick-Artefakte am Sample-Anfang/-Ende. */
    private fun env(i: Int, total: Int): Float {
        val a = (total * 0.04f).toInt().coerceAtLeast(1)
        val r = (total * 0.25f).toInt().coerceAtLeast(1)
        return when {
            i < a -> i / a.toFloat()
            i > total - r -> (total - i) / r.toFloat()
            else -> 1f
        }
    }

    // =====================================================================
    // AudioTrack-Playback (jeweils eine Track pro Sample)
    // =====================================================================

    private val attrs by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
    private val format by lazy {
        AudioFormat.Builder()
            .setSampleRate(SR)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
    }

    private fun play(buf: ShortArray) {
        val byteSize = buf.size * 2
        val minBuf = AudioTrack.getMinBufferSize(
            SR,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(byteSize)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Throwable) { return }

        try {
            track.play()
            track.write(buf, 0, buf.size)
            // Auf Playback-Ende warten — schluckt blocking, läuft eh in eigenem Thread
            Thread.sleep((buf.size * 1000L / SR) + 30L)
        } catch (_: Throwable) {
            /* AudioTrack kann auf manchen Geräten zickig sein — silent fail */
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
    }

    private fun ExecutorService.submitSafe(task: () -> Unit) {
        try { submit(task) } catch (_: Throwable) {}
    }
}
