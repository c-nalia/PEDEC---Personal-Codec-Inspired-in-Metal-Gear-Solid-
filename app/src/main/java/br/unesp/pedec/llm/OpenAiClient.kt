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
 * Cliente para qualquer servico que fale o formato de chat da OpenAI, que na
 * pratica e todo mundo menos a Anthropic: Gemini, Groq, OpenRouter, Cerebras,
 * Ollama e a propria OpenAI.
 *
 * A diferenca entre eles cabe em tres campos: URL base, nome do modelo e chave.
 * Por isso nao existe uma classe por provedor.
 */
class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = 1024,
    /**
     * "low" | "medium" | "high" | "" para omitir.
     *
     * Isto e o que decide a latencia percebida. Os modelos recentes raciocinam
     * ANTES de emitir qualquer token visivel — no Gemini 3.x a camada de
     * compatibilidade mapeia reasoning_effort para thinking_level. Com esforco
     * alto, o streaming existe mas nao sai nada por varios segundos, e a
     * sensacao e exatamente a de esperar o pacote inteiro. Para resposta falada
     * de tres frases, "low" e o certo.
     */
    private val reasoningEffort: String = "low"
) : LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    class ApiException(val code: Int, message: String) : Exception(message)

    override fun stream(
        systemPrompt: String,
        history: List<Conversation.Turn>,
        buscar: ((String) -> String)?,
        onDelta: (String) -> Unit
    ): String = try {
        conversar(systemPrompt, history, buscar, onDelta, withEffort = reasoningEffort.isNotBlank())
    } catch (e: ApiException) {
        // Nem todo provedor compativel conhece reasoning_effort. Em vez de
        // falhar, repete sem ele.
        if (e.code == 400 && reasoningEffort.isNotBlank()) {
            Log.w(TAG, "reasoning_effort recusado, repetindo sem ele")
            conversar(systemPrompt, history, buscar, onDelta, withEffort = false)
        } else throw e
    }

    /**
     * Conduz a conversa ate o modelo parar de pedir ferramentas.
     *
     * Ha uma diferenca importante em relacao ao cliente da Anthropic: nem todo
     * provedor compativel implementa ferramentas de verdade. Alguns modelos
     * gratuitos aceitam o campo e ignoram, outros devolvem 400. Por isso a
     * primeira falha com ferramenta repete SEM ela, em vez de desistir: perder
     * a busca e aceitavel, perder a resposta nao.
     */
    private fun conversar(
        systemPrompt: String,
        history: List<Conversation.Turn>,
        buscar: ((String) -> String)?,
        onDelta: (String) -> Unit,
        withEffort: Boolean
    ): String {
        val mensagens = JSONArray().apply {
            // No formato da OpenAI o prompt de sistema e so a primeira mensagem,
            // e nao um campo separado como na Anthropic.
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            history.forEach { t ->
                put(JSONObject().put("role", t.role).put("content", t.text))
            }
        }

        val texto = StringBuilder()
        var rodada = 0
        var comFerramenta = buscar != null

        while (true) {
            val turno = try {
                doStream(mensagens, comFerramenta, onDelta, withEffort)
            } catch (e: ApiException) {
                if (comFerramenta && (e.code == 400 || e.code == 404)) {
                    Log.w(TAG, "provedor recusou ferramentas; seguindo sem busca")
                    comFerramenta = false
                    continue
                }
                throw e
            }

            if (turno.texto.isNotEmpty()) texto.append(turno.texto)

            val pedido = turno.pedidoDeBusca
            if (pedido == null || buscar == null || ++rodada > MAX_RODADAS) {
                if (rodada > MAX_RODADAS) Log.w(TAG, "teto de buscas atingido")
                return texto.toString()
            }

            Log.i(TAG, "modelo pediu busca: ${pedido.consulta}")
            val resultado = runCatching { buscar(pedido.consulta) }
                .getOrElse { "A busca falhou: ${it.message}" }

            mensagens.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONObject.NULL)
                    .put(
                        "tool_calls", JSONArray().put(
                            JSONObject()
                                .put("id", pedido.id)
                                .put("type", "function")
                                .put(
                                    "function", JSONObject()
                                        .put("name", Ferramentas.BUSCA)
                                        .put(
                                            "arguments",
                                            JSONObject().put(Ferramentas.PARAM, pedido.consulta).toString()
                                        )
                                )
                        )
                    )
            )
            mensagens.put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", pedido.id)
                    .put("content", resultado)
            )
        }
    }

    private class Turno(val texto: String, val pedidoDeBusca: Pedido?)
    private class Pedido(val id: String, val consulta: String)

    private fun doStream(
        mensagens: JSONArray,
        comFerramenta: Boolean,
        onDelta: (String) -> Unit,
        withEffort: Boolean
    ): Turno {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", mensagens)
            put("stream", true)
            put("max_tokens", maxTokens)
            put("temperature", 0.6)
            if (withEffort) put("reasoning_effort", reasoningEffort)
            if (comFerramenta) {
                put(
                    "tools", JSONArray().put(
                        JSONObject().put("type", "function").put(
                            "function", JSONObject()
                                .put("name", Ferramentas.BUSCA)
                                .put("description", Ferramentas.DESCRICAO)
                                .put(
                                    "parameters", JSONObject()
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
                )
            }
        }

        val url = baseUrl.trimEnd('/') + "/chat/completions"

        val builder = Request.Builder()
            .url(url)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))

        // Ollama local nao pede autenticacao; os demais usam Bearer.
        if (apiKey.isNotBlank()) builder.addHeader("authorization", "Bearer $apiKey")

        val sb = StringBuilder()
        var toolId: String? = null
        val argumentos = StringBuilder()

        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw ApiException(resp.code, "HTTP ${resp.code}: ${err.take(400)}")
            }
            val source = resp.body?.source() ?: throw ApiException(-1, "resposta vazia")

            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break

                val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue

                // Alguns provedores devolvem erro dentro do proprio stream,
                // com HTTP 200. Cota estourada costuma chegar assim.
                obj.optJSONObject("error")?.let {
                    throw ApiException(-2, it.optString("message", "erro no stream"))
                }

                val choices = obj.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue

                val piece = delta.optString("content")
                if (piece.isNotEmpty()) {
                    sb.append(piece)
                    onDelta(piece)
                }

                // A chamada de ferramenta chega fatiada, igual ao texto: o id e
                // o nome vem no primeiro pedaco, os argumentos vao pingando em
                // "arguments" como JSON pela metade. So da para interpretar
                // depois que o stream fecha.
                val chamadas = delta.optJSONArray("tool_calls") ?: continue
                for (i in 0 until chamadas.length()) {
                    val c = chamadas.optJSONObject(i) ?: continue
                    c.optString("id").takeIf { it.isNotBlank() }?.let { toolId = it }
                    val f = c.optJSONObject("function") ?: continue
                    argumentos.append(f.optString("arguments"))
                }
            }
        }

        val pedido = toolId?.let { id ->
            val consulta = runCatching {
                JSONObject(argumentos.toString()).optString(Ferramentas.PARAM)
            }.getOrDefault("")
            if (consulta.isBlank()) null else Pedido(id, consulta)
        }

        if (sb.isEmpty() && pedido == null) Log.w(TAG, "stream terminou sem texto")
        return Turno(sb.toString(), pedido)
    }

    private companion object {
        const val TAG = "PedecOpenAi"
        /** Buscas encadeadas por pergunta. */
        const val MAX_RODADAS = 3
        val JSON = "application/json".toMediaType()
    }
}
