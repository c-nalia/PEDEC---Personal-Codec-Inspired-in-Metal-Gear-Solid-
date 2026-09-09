package br.unesp.pedec.web

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Busca na web pela Brave Search API.
 *
 * ## Por que a Brave
 *
 * Precisava atender tres condicoes que ja nos derrubaram antes: cadastro com
 * e-mail comum (o Picovoice pedia corporativo e ficou de fora), cota gratuita
 * util, e resposta em JSON estavel. Raspar HTML de buscador seria mais rapido
 * de escrever e quebraria sozinho na primeira mudanca de layout, sem aviso e
 * sem erro claro.
 *
 * ## O formato importa mais do que parece
 *
 * O resultado daqui nao vai para uma tela: vai para dentro do prompt e acaba
 * virando fala num fone de conducao ossea. Por isso [Resultado.paraModelo]
 * entrega texto corrido enxuto, sem URL crua nem marcacao. URL falada em voz
 * alta e ruido puro, e um resultado gordo demais empurra o historico da
 * conversa para fora da janela de contexto.
 */
class WebSearch(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        // Prazos curtos de proposito: isto roda no meio de uma chamada de voz,
        // com o usuario esperando em silencio. Busca que demora 30 s e pior do
        // que busca que falha rapido e deixa o modelo responder sem ela.
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class Item(val titulo: String, val descricao: String, val site: String)

    data class Resultado(
        val consulta: String,
        val itens: List<Item>,
        val erro: String? = null
    ) {
        /** Texto que volta para o modelo. Compacto, sem URL, pronto para falar. */
        fun paraModelo(): String {
            if (erro != null) return "A busca falhou: $erro. Responda com o que voce ja sabe e avise que nao conseguiu confirmar."
            if (itens.isEmpty()) return "A busca por \"$consulta\" nao retornou nada util."
            return buildString {
                append("Resultados da busca por \"").append(consulta).append("\":\n")
                itens.forEachIndexed { i, it ->
                    append(i + 1).append(". ").append(it.titulo)
                    if (it.site.isNotBlank()) append(" (").append(it.site).append(")")
                    append(": ").append(it.descricao).append('\n')
                }
            }.trim()
        }
    }

    val configurado: Boolean get() = apiKey.isNotBlank()

    /**
     * Nunca lanca. Uma busca que falha vira um [Resultado] com [Resultado.erro],
     * e o modelo recebe isso como informacao — ele avisa que nao confirmou e
     * segue. Deixar a excecao subir abortaria a chamada inteira por causa de
     * um recurso auxiliar.
     */
    fun buscar(consulta: String, quantos: Int = 5): Resultado {
        if (!configurado) {
            return Resultado(consulta, emptyList(), "sem chave da Brave configurada")
        }
        val limpa = consulta.trim().take(300)
        if (limpa.isBlank()) return Resultado(consulta, emptyList(), "consulta vazia")

        return try {
            val url = "https://api.search.brave.com/res/v1/web/search" +
                "?q=" + URLEncoder.encode(limpa, "UTF-8") +
                "&count=" + quantos.coerceIn(1, 10) +
                // pt-BR primeiro: a maioria das perguntas vai ser em portugues,
                // e resultado em ingles seria lido com pronuncia errada.
                "&country=BR&search_lang=pt&safesearch=moderate"

            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("X-Subscription-Token", apiKey)
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val motivo = when (resp.code) {
                        401, 403 -> "chave da Brave invalida"
                        429 -> "cota da Brave esgotada"
                        else -> "HTTP ${resp.code}"
                    }
                    return Resultado(limpa, emptyList(), motivo)
                }
                val corpo = resp.body?.string().orEmpty()
                Resultado(limpa, extrair(corpo, quantos))
            }
        } catch (e: Exception) {
            Log.w(TAG, "busca falhou", e)
            Resultado(limpa, emptyList(), e.message ?: "erro de rede")
        }
    }

    private fun extrair(json: String, quantos: Int): List<Item> {
        val raiz = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val web = raiz.optJSONObject("web") ?: return emptyList()
        val lista = web.optJSONArray("results") ?: return emptyList()

        val saida = ArrayList<Item>(quantos)
        for (i in 0 until minOf(lista.length(), quantos)) {
            val o = lista.optJSONObject(i) ?: continue
            val titulo = limpar(o.optString("title"))
            val descricao = limpar(o.optString("description"))
            if (titulo.isBlank() && descricao.isBlank()) continue
            saida.add(
                Item(
                    titulo = titulo,
                    // Corta cedo: cinco descricoes inteiras somariam mais que a
                    // pergunta e a resposta juntas no contexto.
                    descricao = descricao.take(320),
                    site = dominio(o.optString("url"))
                )
            )
        }
        return saida
    }

    /**
     * A Brave marca os termos da consulta com <strong> dentro da descricao.
     * Sem tirar, o modelo repete a marcacao e o sintetizador tenta ler "strong".
     */
    private fun limpar(s: String): String =
        s.replace(TAGS_HTML, "")
            .replace("&quot;", "\"").replace("&#x27;", "'")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace(ESPACOS, " ")
            .trim()

    /** So o dominio: "g1.globo.com" se pronuncia; a URL inteira, nao. */
    private fun dominio(url: String): String =
        runCatching {
            java.net.URI(url).host.orEmpty().removePrefix("www.")
        }.getOrDefault("")

    private companion object {
        const val TAG = "PedecBusca"
        val TAGS_HTML = Regex("<[^>]*>")
        val ESPACOS = Regex("\\s+")
    }
}
