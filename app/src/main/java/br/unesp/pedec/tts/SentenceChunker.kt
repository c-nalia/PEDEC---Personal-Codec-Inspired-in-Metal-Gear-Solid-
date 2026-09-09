package br.unesp.pedec.tts

/**
 * Corta o texto que chega em streaming do modelo em pedacos falaveis.
 * Emite assim que fecha uma frase, para o TTS comecar cedo, mas evita
 * pedacos curtos demais que sairiam picotados no ouvido.
 */
class SentenceChunker(
    /**
     * A primeira frase sai bem mais curta de proposito. E ela que decide a
     * latencia percebida: o resto ja vai estar sendo sintetizado em paralelo.
     */
    private val firstMinChars: Int = 22,
    private val minChars: Int = 45
) {
    private var emitted = 0

    private val buf = StringBuilder()

    fun feed(delta: String, emit: (String) -> Unit) {
        buf.append(delta)
        while (true) {
            val cut = findCut() ?: return
            val chunk = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (chunk.isNotEmpty()) {
                emitted++
                emit(chunk)
            }
        }
    }

    fun flush(emit: (String) -> Unit) {
        val rest = buf.toString().trim()
        buf.setLength(0)
        if (rest.isNotEmpty()) {
            emitted++
            emit(rest)
        }
    }

    private fun findCut(): Int? {
        val floor = if (emitted == 0) firstMinChars else minChars
        if (buf.length < floor) return null
        for (i in floor - 1 until buf.length) {
            val c = buf[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == ':') {
                // nao corta em abreviacao ou numero decimal
                val next = buf.getOrNull(i + 1)
                if (c == '.' && next != null && next.isDigit()) continue
                if (c == ':' && i <= floor) continue
                return i + 1
            }
        }
        // frase muito longa sem pontuacao: corta na virgula depois de 200 chars
        if (buf.length > 200) {
            val comma = buf.indexOf(",", 120)
            if (comma > 0) return comma + 1
        }
        return null
    }
}
