package br.unesp.pedec.music

import br.unesp.pedec.voice.WakeMatcher

/**
 * Reconhece pedidos de musica dentro da fala, antes de ela virar pergunta.
 *
 * A regra que evita estrago: comando de tocar exige um verbo NO INICIO da
 * frase. "toca pink floyd" e comando; "quem toca baixo no pink floyd" e
 * pergunta. Sem essa ancora, qualquer duvida sobre musica viraria ordem de
 * reproducao e a resposta nunca chegaria.
 */
object MusicCommands {

    sealed class Action {
        /** @param alias apelido salvo, quando bateu com um atalho. */
        data class Play(val query: String, val focus: String, val alias: String?) : Action()
        object Pause : Action()
        object Resume : Action()
        object Next : Action()
        object Previous : Action()
    }

    /** Verbos que abrem um pedido de reproducao. */
    private val PLAY_VERBS = listOf(
        "toca", "tocar", "toque",
        "coloca", "colocar", "coloque",
        // "por" ficou de fora de proposito: normalizado, "por que" comeca
        // metade das perguntas em portugues, e virava ordem de tocar.
        "poe", "ponha",
        "manda", "mandar",
        "bota", "botar"
    )

    /** Palavras descartadas logo apos o verbo: "toca AI o rock". */
    private val FILLERS = setOf("ai", "la", "a", "o", "as", "os", "um", "uma", "pra", "para")

    private val PAUSE = setOf(
        "pausa", "pausar", "pause", "para a musica", "parar a musica",
        "pausa a musica", "para de tocar", "silencio"
    )
    private val RESUME = setOf(
        "continua", "continuar", "retoma", "retomar", "volta a tocar",
        "continua a musica", "despausa"
    )
    private val NEXT = setOf(
        "proxima", "proxima musica", "pula", "pular", "pula essa",
        "passa", "passa a musica", "proxima faixa"
    )
    private val PREVIOUS = setOf(
        "anterior", "musica anterior", "volta", "voltar",
        "volta a musica", "faixa anterior"
    )

    /** Pistas que indicam o tipo do que voce pediu. */
    private val FOCUS_HINTS = mapOf(
        "playlist" to "playlist",
        "lista" to "playlist",
        "album" to "album",
        "disco" to "album",
        "artista" to "artist",
        "banda" to "artist"
    )

    /**
     * @param shortcuts apelido para endereco, vindo da configuracao.
     * @return null quando a fala nao e comando de musica e deve seguir para o
     *         modelo como pergunta.
     */
    fun parse(text: String, shortcuts: Map<String, String> = emptyMap()): Action? {
        val n = WakeMatcher.normalize(text)
        if (n.isBlank()) return null

        // Transporte primeiro: sao frases curtas e fechadas, entao a
        // comparacao exata basta e nao rouba nada de pergunta.
        val tokens = n.split(' ').filter { it.isNotEmpty() }
        if (tokens.size <= 4) {
            if (n in PAUSE) return Action.Pause
            if (n in RESUME) return Action.Resume
            if (n in NEXT) return Action.Next
            if (n in PREVIOUS) return Action.Previous
        }

        // Reproducao: exige verbo na PRIMEIRA palavra.
        val verb = tokens.firstOrNull() ?: return null
        if (verb !in PLAY_VERBS) return null

        var rest = tokens.drop(1)
        // Segunda trava contra pergunta disfarcada: nenhum pedido de musica
        // continua com "que". "coloca que horas sao" e pergunta.
        if (rest.firstOrNull() == "que") return null
        while (rest.isNotEmpty() && rest.first() in FILLERS) rest = rest.drop(1)
        if (rest.isEmpty()) return null

        // Tipo explicito: "toca a playlist foco"
        var focus = ""
        FOCUS_HINTS[rest.firstOrNull()]?.let {
            focus = it
            rest = rest.drop(1)
            while (rest.isNotEmpty() && rest.first() in FILLERS) rest = rest.drop(1)
        }
        if (rest.isEmpty()) return null

        val query = rest.joinToString(" ")

        // Atalho salvo tem prioridade: e o unico jeito de "toca foco" abrir a
        // SUA playlist chamada foco em vez do que a busca achar mais popular.
        shortcuts.entries
            .firstOrNull { WakeMatcher.normalize(it.key) == query }
            ?.let { return Action.Play(it.value, focus, it.key) }

        return Action.Play(query, focus, null)
    }

    /**
     * Le a lista de atalhos escrita na configuracao, um por linha, no formato
     * `apelido = endereco`.
     */
    fun parseShortcuts(raw: String): Map<String, String> =
        raw.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@mapNotNull null
                val alias = line.substring(0, i).trim()
                val uri = line.substring(i + 1).trim()
                if (alias.isBlank() || uri.isBlank()) null else alias to uri
            }
            .toMap()
}
