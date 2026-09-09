package br.unesp.pedec.llm

import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cliente da Messages API da Anthropic, em streaming (SSE).
 *
 * Streaming importa aqui porque a resposta vai virar voz: assim que a primeira
 * frase fecha, o TTS ja pode comecar a falar enquanto o resto ainda chega.
 */
class ClaudeClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-5",
    /** "low" | "medium" | "high" | "xhigh" | "max" | "" para nao enviar. */
    private val effort: String = "medium",
    private val maxTokens: Int = 1024
) : LlmClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    class ApiException(val code: Int, message: String) : Exception(message)

    /**
     * @param onDelta chamado a cada pedaco de texto novo.
     * @return texto completo da resposta.
     */
    override fun stream(
        systemPrompt: String,
        history: List<Conversation.Turn>,
        buscar: ((String) -> String)?,
        onDelta: (String) -> Unit
    ): String {
        return try {
            conversar(systemPrompt, history, buscar, onDelta, withEffort = effort.isNotBlank())
        } catch (e: ApiException) {
            // Se a conta ou o modelo ainda nao aceitarem o parametro de esforco,
            // repete sem ele em vez de estourar na cara do usuario.
            if (e.code == 400 && effort.isNotBlank() &&
                (e.message?.contains("effort", true) == true ||
                    e.message?.contains("output_config", true) == true)
            ) {
                Log.w(TAG, "output_config.effort recusado, repetindo sem ele")
                conversar(systemPrompt, history, buscar, onDelta, withEffort = false)
            } else throw e
        }
    }

    /**
     * Conduz a conversa ate o modelo parar de pedir ferramentas.
     *
     * O laco existe porque uma busca costuma levar a outra: o modelo pesquisa,
     * le o resultado e conclui que precisa de mais um detalhe. O teto de
     * [MAX_RODADAS] evita que um modelo confuso fique pesquisando para sempre
     * com voce esperando calado do outro lado.
     */
    private fun conversar(
        systemPrompt: String,
        history: List<Conversation.Turn>,
        buscar: ((String) -> String)?,
        onDelta: (String) -> Unit,
        withEffort: Boolean
    ): String {
        // Historico proprio desta chamada: o pedido de ferramenta e o resultado
        // dela entram aqui, mas nao voltam para o historico do app. Quem le a
        // conversa depois quer ver pergunta e resposta, nao a mecanica.
        val mensagens = JSONArray()
        history.forEach { t ->
            mensagens.put(JSONObject().put("role", t.role).put("content", t.text))
        }

        val texto = StringBuilder()
        var rodada = 0

        while (true) {
            val turno = doStream(systemPrompt, mensagens, buscar != null, onDelta, withEffort)
            if (turno.texto.isNotEmpty()) texto.append(turno.texto)

            val pedido = turno.pedidoDeBusca
            if (pedido == null || buscar == null || ++rodada > MAX_RODADAS) {
                if (rodada > MAX_RODADAS) Log.w(TAG, "teto de buscas atingido")
                return texto.toString()
            }

            Log.i(TAG, "modelo pediu busca: ${pedido.consulta}")
            val resultado = runCatching { buscar(pedido.consulta) }
                .getOrElse { "A busca falhou: ${it.message}" }

            // O bloco tool_use do assistente e o tool_result do usuario precisam
            // vir no MESMO formato que a API mandou, senao a proxima chamada e
            // recusada com "unexpected tool_use_id".
            mensagens.put(
                JSONObject().put("role", "assistant").put(
                    "content", JSONArray().put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", pedido.id)
                            .put("name", Ferramentas.BUSCA)
                            .put("input", JSONObject().put(Ferramentas.PARAM, pedido.consulta))
                    )
                )
            )
            mensagens.put(
                JSONObject().put("role", "user").put(
                    "content", JSONArray().put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", pedido.id)
                            .put("content", resultado)
                    )
                )
            )
        }
    }

    private class Turno(val texto: String, val pedidoDeBusca: Pedido?)
    private class Pedido(val id: String, val consulta: String)

    private fun doStream(
        systemPrompt: String,
        mensagens: JSONArray,
        comFerramenta: Boolean,
        onDelta: (String) -> Unit,
        withEffort: Boolean
    ): Turno {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            put("messages", mensagens)
            put("stream", true)
            if (comFerramenta) {
                put(
                    "tools", JSONArray().put(
                        JSONObject()
                            .put("name", Ferramentas.BUSCA)
                            .put("description", Ferramentas.DESCRICAO)
                            .put(
                                "input_schema", JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties", JSONObject().put(
                                            Ferramentas.PARAM, JSONObject()
                                                .put("type", "string")
                                                .put("description", Ferramentas.PARAM_DESCRICAO)
                                        )
                                    )
                                    .put("required", JSONArray().put(Ferramentas.PARAM))
                            )
                    )
                )
            }
            // Nivel de esforco vai dentro de output_config (Messages API).
            // O padrao da API e "high"; aqui usamos "medium", que e o passo de
            // economia recomendado para o Sonnet 5.
            if (withEffort) put("output_config", JSONObject().put("effort", effort))
        }

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val sb = StringBuilder()
        var toolId: String? = null
        val argumentos = StringBuilder()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw ApiException(resp.code, "HTTP ${resp.code}: ${err.take(500)}")
            }
            val source = resp.body?.source() ?: throw ApiException(-1, "resposta vazia")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue

                val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                when (obj.optString("type")) {
                    "content_block_start" -> {
                        val bloco = obj.optJSONObject("content_block") ?: continue
                        if (bloco.optString("type") == "tool_use") {
                            // O id vem AGORA; os argumentos chegam depois, em
                            // pedacos. Guardar o id aqui e obrigatorio: sem ele
                            // o tool_result seguinte nao tem a quem responder.
                            toolId = bloco.optString("id")
                            argumentos.setLength(0)
                        }
                    }
                    "content_block_delta" -> {
                        val delta = obj.optJSONObject("delta") ?: continue
                        when (delta.optString("type")) {
                            "text_delta" -> {
                                val piece = delta.optString("text")
                                if (piece.isNotEmpty()) {
                                    sb.append(piece)
                                    onDelta(piece)
                                }
                            }
                            // O argumento da ferramenta vem como JSON fatiado em
                            // pedacos arbitrarios: "{\"cons", "ulta\": \"pre",
                            // "co do dolar\"}". So da para interpretar depois
                            // que o bloco fecha, entao aqui so acumulamos.
                            "input_json_delta" -> argumentos.append(delta.optString("partial_json"))
                        }
                    }
                    "error" -> {
                        val msg = obj.optJSONObject("error")?.optString("message") ?: "erro no stream"
                        throw ApiException(-2, msg)
                    }
                }
            }
        }

        val pedido = toolId?.let { id ->
            val consulta = runCatching {
                JSONObject(argumentos.toString()).optString(Ferramentas.PARAM)
            }.getOrDefault("")
            if (consulta.isBlank()) null else Pedido(id, consulta)
        }
        return Turno(sb.toString(), pedido)
    }

    private companion object {
        const val TAG = "PedecClaude"
        /** Buscas encadeadas por pergunta. Alem disso, e o modelo em laco. */
        const val MAX_RODADAS = 3
        val JSON = "application/json".toMediaType()
    }
}
