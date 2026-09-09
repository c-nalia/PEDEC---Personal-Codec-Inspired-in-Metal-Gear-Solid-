package br.unesp.pedec.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sons do codec gerados por sintese, sem sample nenhum embutido.
 * Nada de asset extraido de jogo: tudo aqui e onda calculada na hora,
 * so inspirada no timbre de chamada de radio.
 */
object CodecSfx {

    private const val SR = 22050

    /** Chamada entrando: par de bipes agudos repetido, com cauda curta. */
    fun ring(repeats: Int = 2): ShortArray {
        val out = ArrayList<Short>(SR)
        repeat(repeats) {
            out.addAll(blip(1046.5, 0.055).toList())
            out.addAll(silence(0.045).toList())
            out.addAll(blip(1396.9, 0.055).toList())
            out.addAll(silence(0.16).toList())
        }
        return out.toShortArray()
    }

    /** Linha aberta: bipe curto de confirmacao, a deixa para voce falar. */
    fun connect(): ShortArray {
        val a = blip(880.0, 0.045)
        val b = blip(1318.5, 0.075)
        return a + silence(0.02) + b
    }

    /** Fim de transmissao: dois tons descendo. */
    fun disconnect(): ShortArray {
        return blip(1046.5, 0.05) + silence(0.02) + blip(659.3, 0.10)
    }

    /**
     * Confirmacao de envio: dois tons curtos subindo.
     *
     * Sintetizado em vez de sair de arquivo de proposito. O itemequip.wav tem
     * 143 ms e um timbre discreto: ele se perde logo depois da sua fala e antes
     * da resposta, que e exatamente o momento em que voce precisa ouvir. Aqui
     * o timbre e escolhido para cortar o resto sem ser estridente.
     */
    fun ack(gain: Float = 1f): ShortArray {
        val a = blip(1318.5, 0.045, 9500.0)
        val b = blip(1975.5, 0.070, 9500.0)
        val out = a + silence(0.02) + b
        if (gain >= 0.99f) return out
        for (i in out.indices) out[i] = (out[i] * gain).toInt().toShort()
        return out
    }

    /** Estatica curta, usada como respiro antes da voz entrar. */
    fun staticBurst(seconds: Double = 0.18): ShortArray {
        val n = (SR * seconds).toInt()
        val rng = Random(7)
        val buf = ShortArray(n)
        val filter = RadioFilter(SR, 1f)
        for (i in 0 until n) {
            val env = exp(-3.0 * i / n)
            buf[i] = ((rng.nextFloat() * 2f - 1f) * 2600f * env).toInt().toShort()
        }
        filter.process(buf)
        return buf
    }

    /**
     * Bipe com dois harmonicos e envelope percussivo. Onda quadrada suavizada
     * da o "digital" que a caixinha de radio do MGS tem.
     */
    private fun blip(freq: Double, seconds: Double, amp: Double = 7200.0): ShortArray {
        val n = (SR * seconds).toInt()
        val buf = ShortArray(n)
        val attack = (SR * 0.004).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val fundamental = sin(2 * PI * freq * t)
            val third = sin(2 * PI * freq * 3 * t) * 0.28
            val fifth = sin(2 * PI * freq * 5 * t) * 0.12
            var env = exp(-5.5 * i / n)
            if (i < attack) env *= i.toDouble() / attack
            buf[i] = ((fundamental + third + fifth) * amp * env)
                .toInt().coerceIn(-32768, 32767).toShort()
        }
        return buf
    }

    private fun silence(seconds: Double) = ShortArray((SR * seconds).toInt())

    /** Toca um efeito de forma sincrona no player informado. */
    fun play(player: PcmPlayer, data: ShortArray) {
        player.start()
        player.write(data)
    }
}
