package br.unesp.pedec.voice

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.min

/**
 * "Pedec" nao existe no lexico de nenhum modelo de portugues, entao o
 * reconhecedor nunca vai devolver essa grafia exata. Ele devolve o que mais se
 * parece: "pedeque", "pe deque", "pedeq", "pedeck".
 *
 * Em vez de brigar com isso, aceitamos uma lista de grafias e comparamos com
 * tolerancia de edicao. E o truque padrao para palavra-chave fora do
 * vocabulario, so que explicito e ajustavel na tela de configuracao.
 */
object WakeMatcher {

    /**
     * Vazia de proposito. Palavra de ativacao e escolha sua: qualquer lista que
     * eu chutasse aqui seria fonetica de outra pessoa, e grafia errada na lista
     * so gera ativacao acidental. Defina pela tela de calibracao, que aprende
     * com a sua voz, ou escrevendo na configuracao.
     *
     * Com a lista vazia a deteccao simplesmente nunca dispara, e o botao
     * CHAMAR AGORA continua abrindo a linha.
     */
    val DEFAULT_PHRASES: List<String> = emptyList()

    /**
     * Palavras do portugues que caem dentro da tolerancia de edicao mas nunca
     * sao a ativacao. "pede" fica a uma edicao de "pedec" e aparece o tempo
     * todo em conversa normal; sem essa lista o assistente atende sozinho.
     */
    private val BLOCKLIST = setOf(
        "pede", "pedem", "pedes", "pedeu", "pedia", "pedir", "pediu",
        "pedaco", "pedido", "pedra", "pedal", "peden",
        // entraram junto com as variantes p/b e d/t
        "bebida", "beber", "bebe", "petisco", "peteca"
    )

    /** Abaixo disso a distancia de edicao nao discrimina mais nada de util. */
    private const val MIN_FUZZY_LEN = 5

    /** @param rest o que sobrou depois da ativacao: ja pode ser a pergunta. */
    data class Hit(val rest: String)

    /**
     * Procura a palavra de ativacao e devolve o restante da frase.
     * Compara token a token e tambem pares de tokens, porque o reconhecedor
     * costuma quebrar "pedec" em "pe dec".
     */
    fun find(text: String, phrases: List<String>): Hit? {
        if (text.isBlank()) return null
        val tokens = normalize(text).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val preparadas = preparar(phrases)

        for (i in tokens.indices) {
            if (preparadas.simples.any { close(tokens[i], it) }) {
                return Hit(tokens.drop(i + 1).joinToString(" "))
            }
            if (i + 1 < tokens.size) {
                val par = tokens[i] + " " + tokens[i + 1]
                if (preparadas.duplas.any { close(par, it) }) {
                    return Hit(tokens.drop(i + 2).joinToString(" "))
                }
            }
        }
        return null
    }

    private class Preparadas(val simples: List<String>, val duplas: List<String>)

    /**
     * Normaliza a lista de grafias uma vez e guarda o resultado.
     *
     * A lista quase nunca muda — so quando voce edita na configuracao — mas era
     * renormalizada a cada resultado parcial do reconhecedor. O cache guarda a
     * lista de origem por identidade: se ela for a mesma instancia, o trabalho
     * ja esta feito.
     */
    @Volatile private var cacheOrigem: List<String>? = null
    @Volatile private var cachePronto: Preparadas? = null

    private fun preparar(phrases: List<String>): Preparadas {
        val pronto = cachePronto
        if (pronto != null && cacheOrigem === phrases) return pronto

        val normalizadas = phrases.map { normalize(it) }.filter { it.isNotEmpty() }
        val novo = Preparadas(
            simples = normalizadas.filter { !it.contains(' ') },
            duplas = normalizadas.filter { it.contains(' ') }
        )
        cacheOrigem = phrases
        cachePronto = novo
        return novo
    }

    /**
     * Tolerancia proporcional: ate 6 letras aceita 1 edicao, de 7 em diante
     * aceita 2. Sem isso "pedeque" contra "pedequi" escapa.
     *
     * A igualdade exata passa antes de qualquer filtro: se voce listou uma
     * grafia na configuracao, ela vale mesmo que seja curta ou comum. Os
     * filtros existem so para segurar a comparacao aproximada.
     */
    private fun close(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < MIN_FUZZY_LEN) return false
        if (a in BLOCKLIST) return false
        if (abs(a.length - b.length) > 2) return false
        // Mesmo comeco de palavra: derruba a maior parte dos falsos positivos
        // sem custar nada nas variacoes de terminacao, que e onde a transcricao
        // realmente varia. Duas letras, nao tres: a terceira ja e onde comeca a
        // divergir entre "pedec", "petec" e "pedeque".
        if (a.take(2) != b.take(2)) return false
        val budget = if (b.length >= 7) 2 else 1
        return levenshtein(a, b, budget) <= budget
    }

    /** Levenshtein com corte: desiste assim que passa do orcamento. */
    private fun levenshtein(a: String, b: String, budget: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var best = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                if (cur[j] < best) best = cur[j]
            }
            if (best > budget) return budget + 1
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    // Compiladas uma vez, no carregamento da classe.
    //
    // Antes, `Regex("...")` ficava DENTRO de normalize, e Regex compila o
    // padrao no construtor. Como normalize roda na thread de audio a cada
    // resultado parcial do Vosk — e uma vez por grafia da lista, alem do texto
    // — eram tres compilacoes vezes seis chamadas, dez vezes por segundo. Isso
    // e trabalho jogado fora e, pior, lixo gerado exatamente na thread onde
    // este motor ja evita alocacao para nao levar pausa de coleta no meio da
    // captura.
    private val ACENTOS = Regex("\\p{Mn}+")
    private val NAO_ALFANUM = Regex("[^a-z0-9 ]")
    private val ESPACOS = Regex(" +")

    /** Minusculas, sem acento, sem pontuacao. */
    fun normalize(s: String): String {
        val semAcento = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(ACENTOS, "")
        return semAcento
            .replace(NAO_ALFANUM, " ")
            .replace(ESPACOS, " ")
            .trim()
    }

    /**
     * Transforma as amostras da calibracao numa lista de grafias.
     *
     * A ideia por tras: em vez de eu adivinhar como o reconhecedor escreve a
     * sua pronuncia, voce fala algumas vezes e ele mesmo diz. O que ele
     * escreveu VAI virar grafia aceita, com correspondencia exata, entao passa
     * por cima de qualquer filtro heuristico.
     *
     * @param keepDefaults soma as grafias que ja vinham no app.
     */
    fun mergeLearned(samples: List<String>, keepDefaults: Boolean = true): List<String> {
        val learned = samples
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .map { sample ->
                // Palavra de ativacao nao tem cinco palavras. Se o reconhecedor
                // devolveu uma frase, ficamos com os dois primeiros tokens:
                // ele costuma quebrar "pedec" em "pe dec".
                sample.split(' ').filter { it.isNotEmpty() }.take(2).joinToString(" ")
            }
            .filter { it.length >= 3 }
            .distinct()

        val base = if (keepDefaults) DEFAULT_PHRASES else emptyList()
        return (learned + base).distinct()
    }

    /**
     * true se a grafia e uma palavra comum do portugues. Serve para a tela de
     * calibracao avisar: aceitar isso faz o assistente atender sozinho no meio
     * de uma conversa.
     */
    fun isRisky(phrase: String): Boolean {
        val n = normalize(phrase)
        return n in BLOCKLIST || n.length < 4
    }

    fun parsePhrases(raw: String): List<String> =
        raw.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
}
