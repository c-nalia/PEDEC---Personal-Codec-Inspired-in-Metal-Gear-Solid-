package br.unesp.pedec.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Transcricao pelo reconhecedor do proprio Android.
 *
 * Existe porque o Vosk pequeno tem limite estrutural, nao de ajuste: o lexico
 * dele e fixo em portugues, entao soletrar letra por letra e falar palavras em
 * ingles sao coisas que ele nao consegue produzir — qualquer termo fora do
 * dicionario vira a palavra conhecida mais parecida. O motor do Google nao tem
 * essa limitacao.
 *
 * O preco: ele exige o microfone so para si. O VoiceEngine solta a captura
 * antes e retoma depois, e por isso a palavra de ativacao continua no Vosk —
 * vigiar uma palavra conhecida e o que ele faz bem, de graca e sem rede.
 */
class AndroidStt(private val ctx: Context) {

    sealed class Result {
        data class Ok(val text: String) : Result()
        object Silence : Result()
        data class Error(val code: Int, val message: String) : Result()
    }

    private val main = Handler(Looper.getMainLooper())

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    /**
     * @param preferOffline usa o pacote de idioma baixado, sem rede. Precisa
     *        estar instalado nas configuracoes do aparelho.
     * @param endSilenceMs silencio que encerra a fala.
     */
    suspend fun listen(
        preferOffline: Boolean,
        endSilenceMs: Int,
        onPartial: (String) -> Unit
    ): Result = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                cont.resume(Result.Error(-1, "reconhecimento do Android indisponivel"))
                return@suspendCancellableCoroutine
            }

            val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
            var finished = false

            fun finish(r: Result) {
                if (finished) return
                finished = true
                main.post { runCatching { recognizer.destroy() } }
                if (cont.isActive) cont.resume(r)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(Result.Silence)
                        else -> {
                            Log.w(TAG, "erro $error")
                            finish(Result.Error(error, describe(error)))
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()
                    if (text.isEmpty()) finish(Result.Silence) else finish(Result.Ok(text))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.let { if (it.isNotBlank()) onPartial(it) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                // Permite termo em ingles no meio da fala em portugues, que e o
                // caso o tempo todo em assunto tecnico.
                putExtra(
                    RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES,
                    arrayListOf("pt-BR", "en-US")
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    endSilenceMs.toLong()
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    endSilenceMs.toLong()
                )
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 900L)
            }

            cont.invokeOnCancellation {
                main.post { runCatching { recognizer.cancel(); recognizer.destroy() } }
            }

            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                finish(Result.Error(-2, e.message ?: "falha ao iniciar"))
            }
        }
    }

    private fun describe(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "erro de audio"
        SpeechRecognizer.ERROR_CLIENT -> "erro no cliente de reconhecimento"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permissao de microfone negada"
        SpeechRecognizer.ERROR_NETWORK -> "sem rede (baixe o pacote offline de pt-BR)"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "tempo esgotado na rede"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "reconhecedor ocupado"
        SpeechRecognizer.ERROR_SERVER -> "erro no servidor de reconhecimento"
        else -> "erro $code"
    }

    private companion object { const val TAG = "PedecAndroidStt" }
}
