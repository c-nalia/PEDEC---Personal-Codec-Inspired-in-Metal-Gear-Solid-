package br.unesp.pedec.voice

/**
 * Comandos ditos dentro da chamada, que nao devem virar pergunta para o modelo.
 *
 * O criterio e proposital e conservador: o comando precisa SER a fala inteira,
 * nao aparecer dentro dela. "Desliga" encerra; "me explica como desliga um
 * processo no linux" e pergunta. Sem essa regra, qualquer duvida sobre desligar
 * alguma coisa derrubaria a chamada.
 */
object VoiceCommands {

    val DEFAULT_HANGUP = listOf(
        "desligar", "desliga", "desligue",
        "encerrar", "encerra", "encerre",
        "cambio e desligo", "cambio desligo",
        "fim de transmissao", "fim de papo",
        "tchau", "ate mais", "obrigado e tchau",
        "cancela", "cancelar"
    )

    /** Ate quantas palavras a fala pode ter para ainda contar como comando. */
    private const val MAX_TOKENS = 4

    fun isHangUp(text: String, phrases: List<String> = DEFAULT_HANGUP): Boolean {
        val n = WakeMatcher.normalize(text)
        if (n.isBlank()) return false
        if (n.split(' ').size > MAX_TOKENS) return false
        return phrases.any { WakeMatcher.normalize(it) == n }
    }

    /**
     * Junta o que voce falou na calibracao com as frases padrao.
     * Diferente da palavra de ativacao, aqui aceitamos ate 4 palavras: comando
     * de desligar costuma ser uma frase curta, nao um termo isolado.
     */
    fun mergeLearned(samples: List<String>, keepDefaults: Boolean = true): List<String> {
        val learned = samples
            .map { WakeMatcher.normalize(it) }
            .filter { it.isNotBlank() }
            .filter { it.split(' ').size <= MAX_TOKENS }
            .distinct()
        val base = if (keepDefaults) DEFAULT_HANGUP else emptyList()
        return (learned + base).distinct()
    }

    fun parse(raw: String): List<String> {
        val list = raw.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
        return list.ifEmpty { DEFAULT_HANGUP }
    }
}
