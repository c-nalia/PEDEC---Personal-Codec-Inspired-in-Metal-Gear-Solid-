package br.unesp.pedec.audio

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Carrega os WAV de assets/sfx/ em memoria e devolve PCM pronto para o
 * PcmPlayer. O banco inteiro da menos de 1 MB descompactado, entao vale
 * manter em cache em vez de reler o asset a cada chamada.
 *
 * Se um arquivo faltar, cai para o efeito sintetizado do CodecSfx. O app
 * continua funcionando mesmo com a pasta sfx/ vazia.
 */
object SfxBank {

    private const val TAG = "PedecSfx"
    private const val SR = 22050

    private val cache = ConcurrentHashMap<String, ShortArray>()

    /** Duracao maxima de cada efeito no modo curto, em milissegundos. */
    private val shortLimits = mapOf(
        Sfx.ALERT to 700,
        Sfx.CALL to 1100,
        Sfx.OPEN to 700,
        Sfx.OVER to 800,
        Sfx.FAIL to 400,
        Sfx.FATAL to 2500
    )

    fun get(ctx: Context, sfx: Sfx): ShortArray? =
        cache.getOrPut(sfx.asset) {
            try {
                ctx.assets.open(sfx.path).use { decodeWav(it.readBytes()) }
            } catch (e: Exception) {
                Log.w(TAG, "asset ${sfx.path} indisponivel: ${e.message}")
                ShortArray(0)
            }
        }.takeIf { it.isNotEmpty() }

    /**
     * Efeito pronto para tocar, ja com corte de duracao e volume aplicados.
     *
     * @param full false corta os efeitos longos para nao atrasar a conversa.
     */
    fun render(ctx: Context, sfx: Sfx, full: Boolean, volume: Float): ShortArray? {
        val raw = get(ctx, sfx) ?: return null
        val limit = if (full) null else shortLimits[sfx]
        val cut = if (limit == null) raw else trim(raw, limit, fadeMs = 90)
        return if (volume >= 0.99f) cut else gain(cut, volume)
    }

    /** Corta em maxMs e aplica fade de saida, para nao estalar no corte. */
    private fun trim(data: ShortArray, maxMs: Int, fadeMs: Int): ShortArray {
        val maxSamples = maxMs * SR / 1000
        if (data.size <= maxSamples) return data
        val out = data.copyOf(maxSamples)
        val fade = min(fadeMs * SR / 1000, out.size)
        for (i in 0 until fade) {
            val k = 1f - i.toFloat() / fade
            val idx = out.size - fade + i
            out[idx] = (out[idx] * k).toInt().toShort()
        }
        return out
    }

    private fun gain(data: ShortArray, g: Float): ShortArray {
        val out = ShortArray(data.size)
        for (i in data.indices) {
            out[i] = (data[i] * g).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * Leitor de WAV PCM. Aceita mono ou estereo e qualquer taxa; converte para
     * mono 22050 Hz. Os arquivos que acompanham o projeto ja chegam assim, mas
     * o leitor aguenta se voce trocar um deles por outro qualquer.
     *
     * Publico porque o TTS do Android tambem passa por aqui: ele sintetiza em
     * WAV e a taxa varia conforme o motor instalado.
     */
    /**
     * ESTE METODO TINHA CINCO CAMINHOS DE CRASH, e nao eram teoricos: ele
     * decodifica tambem o WAV que o TTS do Android sintetiza, cujo formato
     * varia conforme o motor instalado no aparelho.
     *
     * O que quebrava, tudo com arquivo malformado ou truncado:
     *
     *  1. channels = 0  -> divisao por zero em `total / channels`
     *  2. rate = 0      -> divisao por zero dentro do resample
     *  3. tamanho de chunk >= 2 GB ou lixo -> u32 devolve Int NEGATIVO (o shl 24
     *     de um byte >= 0x80 estoura o sinal), e `ShortArray(negativo)` lanca
     *     NegativeArraySizeException
     *  4. chunk fmt truncado -> u16(body + 14) le fora do array
     *  5. tamanho de chunk negativo -> `p` nao avanca e o laco NUNCA TERMINA,
     *     travando a thread que chamou
     *
     * A correcao trata o arquivo como dado hostil: nada e lido sem conferir o
     * limite, e todo valor do cabecalho e validado antes de virar aritmetica.
     */
    fun decodeWav(bytes: ByteArray): ShortArray {
        // u32 devolve Long, nao Int. E o conserto da raiz do problema 3: em Int
        // de 32 bits com sinal, um tamanho acima de 2 GB vira negativo em
        // silencio e contamina toda a aritmetica seguinte.
        fun u16(o: Int): Int {
            require(o >= 0 && o + 1 < bytes.size) { "WAV truncado" }
            return (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
        }
        fun u32(o: Int): Long {
            require(o >= 0 && o + 3 < bytes.size) { "WAV truncado" }
            return ((bytes[o].toInt() and 0xFF).toLong()) or
                ((bytes[o + 1].toInt() and 0xFF).toLong() shl 8) or
                ((bytes[o + 2].toInt() and 0xFF).toLong() shl 16) or
                ((bytes[o + 3].toInt() and 0xFF).toLong() shl 24)
        }
        fun tag(o: Int): String =
            if (o < 0 || o + 4 > bytes.size) "" else String(bytes, o, 4, Charsets.US_ASCII)

        if (bytes.size < 44 || tag(0) != "RIFF" || tag(8) != "WAVE") {
            throw IllegalArgumentException("nao e um WAV valido")
        }

        var channels = 1
        var rate = SR
        var bits = 16
        var dataOffset = -1
        var dataLength = 0

        var p = 12
        while (p + 8 <= bytes.size) {
            val id = tag(p)
            val size = u32(p + 4)
            val body = p + 8
            when (id) {
                "fmt " -> {
                    // O chunk fmt canonico tem 16 bytes; so lemos o que couber.
                    if (body + 15 < bytes.size) {
                        channels = u16(body + 2)
                        rate = u32(body + 4).toInt()
                        bits = u16(body + 14)
                    }
                }
                "data" -> {
                    dataOffset = body
                    dataLength = size.coerceAtMost((bytes.size - body).toLong()).toInt()
                }
            }
            if (dataOffset >= 0 && id == "data") break

            // Avanco sempre positivo. Sem este piso, um tamanho corrompido
            // fazia `p` ficar parado ou andar para tras, e o laco rodava para
            // sempre com a thread presa dentro dele.
            val avanco = (size + (size and 1L)).coerceIn(1L, bytes.size.toLong())
            p = body + avanco.toInt()
        }

        if (dataOffset < 0) throw IllegalArgumentException("chunk data ausente")
        if (bits != 16) throw IllegalArgumentException("so PCM de 16 bits, veio $bits")
        // Limites de sanidade: fora disto nao e audio, e virar aritmetica so
        // produziria crash mais adiante, longe da causa.
        if (channels !in 1..8) throw IllegalArgumentException("canais invalidos: $channels")
        if (rate !in 4000..192000) throw IllegalArgumentException("taxa invalida: $rate")
        if (dataLength <= 1) return ShortArray(0)

        val total = dataLength / 2
        val interleaved = ShortArray(total)
        for (i in 0 until total) {
            val o = dataOffset + i * 2
            if (o + 1 >= bytes.size) break
            interleaved[i] = ((bytes[o].toInt() and 0xFF) or (bytes[o + 1].toInt() shl 8)).toShort()
        }

        val mono = if (channels == 1) interleaved else ShortArray(total / channels) { f ->
            var acc = 0
            for (c in 0 until channels) acc += interleaved[f * channels + c]
            (acc / channels).toShort()
        }

        return if (rate == SR) mono else resample(mono, rate, SR)
    }

    /** Reamostragem linear. Suficiente para efeito curto. */
    private fun resample(data: ShortArray, from: Int, to: Int): ShortArray {
        if (data.isEmpty()) return data
        val outSize = (data.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = ShortArray(outSize)
        for (i in 0 until outSize) {
            val src = i.toDouble() * from / to
            val i0 = src.toInt().coerceIn(0, data.size - 1)
            val i1 = (i0 + 1).coerceAtMost(data.size - 1)
            val frac = src - i0
            out[i] = (data[i0] * (1 - frac) + data[i1] * frac).toInt().toShort()
        }
        return out
    }
}
