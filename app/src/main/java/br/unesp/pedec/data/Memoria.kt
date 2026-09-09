package br.unesp.pedec.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Memoria de longo prazo do codec.
 *
 * Guarda fatos duraveis sobre voce — curso, projetos, preferencias, nomes que
 * aparecem sempre — para que o assistente nao recomece do zero a cada chamada.
 *
 * ## Por que arquivo separado, e nao mais uma preferencia
 *
 * As preferencias sao chave e valor curtos; isto e uma colecao que cresce, e
 * que voce precisa conseguir revisar e apagar item por item. Um JSON no
 * filesDir do app tambem simplifica a inspecao durante o desenvolvimento.
 *
 * Nao e cifrado, ao contrario das chaves de API. Fica no diretorio privado do
 * app, que outro aplicativo nao le sem root, e o conteudo aqui e "o Chris
 * estuda na UNESP", nao credencial. Se um dia guardar algo sensivel, vale
 * mover para o EncryptedSharedPreferences.
 *
 * ## O limite existe por um motivo pratico
 *
 * Tudo isto entra no prompt de sistema, em toda chamada. Sem teto, a memoria
 * cresceria sem parar, empurraria a conversa para fora da janela de contexto e
 * aumentaria o custo de cada pergunta. Ao encher, o fato mais antigo sai.
 */
class Memoria private constructor(private val arquivo: File) {

    data class Fato(
        val texto: String,
        val quando: Long = System.currentTimeMillis()
    )

    private val fatos = ArrayList<Fato>()

    init {
        carregar()
    }

    @Synchronized
    fun listar(): List<Fato> = fatos.toList()

    @Synchronized
    fun vazia(): Boolean = fatos.isEmpty()

    /**
     * Grava um fato novo.
     *
     * Rejeita repetido comparando o texto normalizado. Sem isso, "estuda
     * Ciencia da Computacao" e "Estuda ciencia da computacao." virariam duas
     * entradas, e em algumas conversas a memoria inteira seria a mesma frase
     * escrita de dez formas.
     */
    @Synchronized
    fun lembrar(texto: String): Boolean {
        val limpo = texto.trim().trim('"', '-', '*', '.').trim()
        if (limpo.length < 4 || limpo.length > LIMITE_TAMANHO) return false

        val chave = normalizar(limpo)
        if (fatos.any { normalizar(it.texto) == chave }) return false

        fatos.add(Fato(limpo))
        while (fatos.size > LIMITE_FATOS) fatos.removeAt(0)
        salvar()
        return true
    }

    @Synchronized
    fun esquecer(indice: Int) {
        if (indice in fatos.indices) {
            fatos.removeAt(indice)
            salvar()
        }
    }

    @Synchronized
    fun limpar() {
        fatos.clear()
        salvar()
    }

    /**
     * O bloco que entra no prompt de sistema. Vazio quando nao ha nada, para
     * nao gastar tokens com cabecalho de secao sem conteudo.
     */
    @Synchronized
    fun paraPrompt(): String {
        if (fatos.isEmpty()) return ""
        return buildString {
            append("\n\nO QUE VOCE JA SABE SOBRE ELE (de conversas anteriores):\n")
            fatos.forEach { append("- ").append(it.texto).append('\n') }
            append(
                "Use isto quando for util, sem anunciar que lembrou e sem repetir " +
                    "de volta o que ele ja sabe sobre si mesmo."
            )
        }
    }

    // ------------------------------------------------------------ persistencia

    private fun carregar() {
        if (!arquivo.exists()) return
        runCatching {
            val raiz = JSONArray(arquivo.readText())
            for (i in 0 until raiz.length()) {
                val o = raiz.optJSONObject(i) ?: continue
                val t = o.optString("texto")
                if (t.isNotBlank()) fatos.add(Fato(t, o.optLong("quando")))
            }
        }.onFailure {
            // Arquivo corrompido nao pode impedir o app de abrir. Perder a
            // memoria e ruim; nao abrir e pior.
            Log.w(TAG, "memoria ilegivel; comecando vazia", it)
            fatos.clear()
        }
    }

    private fun salvar() {
        runCatching {
            val raiz = JSONArray()
            fatos.forEach {
                raiz.put(JSONObject().put("texto", it.texto).put("quando", it.quando))
            }
            arquivo.parentFile?.mkdirs()
            arquivo.writeText(raiz.toString())
        }.onFailure { Log.e(TAG, "falha ao gravar a memoria", it) }
    }

    private fun normalizar(s: String): String =
        s.lowercase().replace(NAO_ALFANUM, " ").replace(ESPACOS, " ").trim()

    companion object {
        private const val TAG = "PedecMemoria"
        private val NAO_ALFANUM = Regex("[^a-z0-9à-ú ]")
        private val ESPACOS = Regex(" +")
        const val LIMITE_FATOS = 40
        const val LIMITE_TAMANHO = 200

        @Volatile private var instancia: Memoria? = null

        fun get(ctx: Context): Memoria = instancia ?: synchronized(this) {
            instancia ?: Memoria(
                File(ctx.applicationContext.filesDir, "memoria.json")
            ).also { instancia = it }
        }

        /**
         * Instrucao que pede a extracao dos fatos ao fim da chamada.
         *
         * Deliberadamente exigente sobre o que NAO guardar. A tentacao do
         * modelo e resumir a conversa inteira; o que serve aqui e so o que
         * continua verdade daqui a um mes. "Ele perguntou sobre quicksort" e
         * lixo na proxima semana; "faz Ciencia da Computacao na UNESP" nao.
         */
        val PROMPT_EXTRACAO = """
            Analise a conversa e extraia APENAS fatos duraveis sobre o usuario que
            valham a pena lembrar em conversas futuras: nome, curso, instituicao,
            projetos em andamento, ferramentas que usa, preferencias de estilo,
            decisoes tecnicas que ele tomou.

            NAO extraia: o que ele perguntou, o que voce respondeu, assuntos
            pontuais, nada que deixe de ser verdade em um mes, nem nada que voce
            ja tenha na lista de coisas que sabe.

            Responda SOMENTE com um array JSON de strings curtas, em portugues,
            cada uma um fato independente. Se nao houver nada que valha a pena,
            responda exatamente [].

            Exemplo: ["Estuda Ciencia da Computacao na UNESP", "Usa um fone de conducao ossea"]
        """.trimIndent()

        /** Le o array JSON devolvido pela extracao, tolerando lixo em volta. */
        fun lerExtracao(resposta: String): List<String> {
            val inicio = resposta.indexOf('[')
            val fim = resposta.lastIndexOf(']')
            if (inicio < 0 || fim <= inicio) return emptyList()
            return runCatching {
                val arr = JSONArray(resposta.substring(inicio, fim + 1))
                (0 until arr.length()).mapNotNull {
                    arr.optString(it).trim().takeIf { s -> s.isNotBlank() }
                }
            }.getOrDefault(emptyList())
        }
    }
}
