package br.unesp.pedec.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Deixa o PCM com cara de radio militar: passa-faixa de 300 a 3000 Hz,
 * saturacao suave e um chiado de portadora por cima.
 *
 * Processa em streaming: o estado dos biquads sobrevive entre chamadas,
 * entao chunks consecutivos nao estalam nas emendas.
 */
class RadioFilter(
    private val sampleRate: Int,
    /** 0f = voz limpa, 1f = codec pesado. */
    private val mix: Float = 0.75f
) {
    private val hp = Biquad.highPass(sampleRate, 320.0, 0.707)
    private val lp = Biquad.lowPass(sampleRate, 3000.0, 0.707)
    private val peak = Biquad.peaking(sampleRate, 1800.0, 1.2, 6.0)
    private val rng = Random(1337)

    private val drive = 1.0f + 3.0f * mix
    private val hissLevel = 260f * mix
    private val wet = mix.coerceIn(0f, 1f)

    fun process(buf: ShortArray, len: Int = buf.size) {
        for (i in 0 until len) {
            val dry = buf[i].toFloat()
            var x = dry.toDouble()
            x = hp.run(x)
            x = lp.run(x)
            x = peak.run(x)
            // saturacao suave (transmissor no limite)
            var y = (tanh(x * drive / 12000.0) * 11000.0).toFloat()
            // chiado da portadora
            y += (rng.nextFloat() * 2f - 1f) * hissLevel
            val out = dry * (1f - wet) + y * wet
            buf[i] = out.coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }
}

/** Biquad direct form I. */
class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var x1 = 0.0; private var x2 = 0.0
    private var y1 = 0.0; private var y2 = 0.0

    fun run(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    companion object {
        fun lowPass(sr: Int, freq: Double, q: Double): Biquad {
            val w = 2.0 * PI * freq / sr
            val alpha = sin(w) / (2 * q)
            val cosw = cos(w)
            val a0 = 1 + alpha
            return Biquad(
                ((1 - cosw) / 2) / a0, (1 - cosw) / a0, ((1 - cosw) / 2) / a0,
                (-2 * cosw) / a0, (1 - alpha) / a0
            )
        }

        fun highPass(sr: Int, freq: Double, q: Double): Biquad {
            val w = 2.0 * PI * freq / sr
            val alpha = sin(w) / (2 * q)
            val cosw = cos(w)
            val a0 = 1 + alpha
            return Biquad(
                ((1 + cosw) / 2) / a0, (-(1 + cosw)) / a0, ((1 + cosw) / 2) / a0,
                (-2 * cosw) / a0, (1 - alpha) / a0
            )
        }

        fun peaking(sr: Int, freq: Double, q: Double, gainDb: Double): Biquad {
            val a = Math.pow(10.0, gainDb / 40.0)
            val w = 2.0 * PI * freq / sr
            val alpha = sin(w) / (2 * q)
            val cosw = cos(w)
            val a0 = 1 + alpha / a
            return Biquad(
                (1 + alpha * a) / a0, (-2 * cosw) / a0, (1 - alpha * a) / a0,
                (-2 * cosw) / a0, (1 - alpha / a) / a0
            )
        }
    }
}
