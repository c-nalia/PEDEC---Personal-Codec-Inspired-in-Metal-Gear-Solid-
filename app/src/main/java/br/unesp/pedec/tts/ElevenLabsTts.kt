package br.unesp.pedec.tts

import android.util.Log
import br.unesp.pedec.audio.PcmPlayer
import br.unesp.pedec.audio.RadioFilter
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Voz da resposta. Pede PCM cru de 22050 Hz ao ElevenLabs, passa cada chunk
 * pelo filtro de radio e joga direto no AudioTrack. Como e streaming, a fala
 * comeca antes do arquivo inteiro chegar.
 */
class ElevenLabsTts(
    private val apiKey: String,
    private val voiceId: String,
    private val modelId: String = "eleven_multilingual_v2",
    private val radioMix: Float = 0.75f
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    class TtsException(message: String) : Exception(message)

    /**
     * Sintetiza e toca de forma sincrona. Volta quando o texto acabou de sair.
     * Um RadioFilter por frase mantem o estado dos biquads continuo dentro dela.
     */
    fun speak(text: String, player: PcmPlayer) {
        if (text.isBlank()) return
        if (apiKey.isBlank() || voiceId.isBlank()) throw TtsException("chave ou voz do ElevenLabs nao configurada")

        val body = JSONObject().apply {
            put("text", text)
            put("model_id", modelId)
            put("voice_settings", JSONObject().apply {
                put("stability", 0.45)
                put("similarity_boost", 0.8)
                put("style", 0.15)
                put("use_speaker_boost", true)
            })
        }

        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream" +
            "?output_format=pcm_22050&optimize_streaming_latency=3"

        val req = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .addHeader("accept", "audio/pcm")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw TtsException("ElevenLabs HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
            }
            val input = resp.body?.byteStream() ?: throw TtsException("audio vazio")
            val filter = RadioFilter(22050, radioMix)
            val raw = ByteArray(4096)
            val pcm = ShortArray(4096)
            player.start()

            var carry = -1
            while (true) {
                if (player.stopped) break
                val read = input.read(raw)
                if (read <= 0) break

                var si = 0
                var bi = 0
                if (carry >= 0) {
                    pcm[si++] = ((raw[0].toInt() and 0xFF shl 8) or carry).toShort()
                    bi = 1
                    carry = -1
                }
                while (bi + 1 < read) {
                    val lo = raw[bi].toInt() and 0xFF
                    val hi = raw[bi + 1].toInt()
                    pcm[si++] = ((hi shl 8) or lo).toShort()
                    bi += 2
                }
                if (bi < read) carry = raw[bi].toInt() and 0xFF

                filter.process(pcm, si)
                player.write(pcm, si)
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val TAG = "PedecEleven"

        /** Lista as vozes da conta, para a tela de configuracao. */
        fun listVoices(apiKey: String): List<Voice> {
            if (apiKey.isBlank()) return emptyList()
            val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/voices")
                .addHeader("xi-api-key", apiKey)
                .get()
                .build()
            return try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return emptyList()
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val arr = json.optJSONArray("voices") ?: return emptyList()
                    (0 until arr.length()).map { i ->
                        val v = arr.getJSONObject(i)
                        val labels = v.optJSONObject("labels")
                        val desc = labels?.keys()?.asSequence()
                            ?.joinToString(", ") { k -> labels.optString(k) }
                            .orEmpty()
                        Voice(
                            id = v.optString("voice_id"),
                            name = v.optString("name"),
                            labels = desc
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "falha ao listar vozes", e)
                emptyList()
            }
        }
    }
}
