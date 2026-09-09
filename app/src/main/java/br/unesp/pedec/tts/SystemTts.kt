package br.unesp.pedec.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import br.unesp.pedec.audio.PcmPlayer
import br.unesp.pedec.audio.RadioFilter
import br.unesp.pedec.audio.SfxBank
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Voz local, pelo motor de TTS do proprio Android.
 *
 * O ponto delicado: TextToSpeech.speak() toca o audio internamente e nunca te
 * entrega o PCM, entao o filtro de radio nao tem por onde entrar. Por isso aqui
 * usamos synthesizeToFile() e reconstruimos o caminho a mao:
 *
 *   sintetiza em WAV -> decodifica -> RadioFilter -> PcmPlayer
 *
 * Custa a espera de uma frase antes de comecar a falar, o que e pouco porque o
 * SentenceChunker ja corta o texto em frases. Em troca, a voz local passa pelo
 * mesmo tratamento da voz paga: passa-faixa, saturacao e chiado. E justamente
 * esse tratamento que disfarca a artificialidade da sintese local — as
 * frequencias onde ela soa robotica sao as que o filtro derruba.
 */
class SystemTts(private val ctx: Context) {

    // O TextToSpeech precisa nascer numa thread com Looper. Criado direto de
    // uma corrotina de IO ele as vezes nem inicializa, e a voz simplesmente
    // nao sai — sem erro visivel.
    init {
        require(true)
    }

    data class VoiceOption(val id: String, val label: String)

    private val ready = CountDownLatch(1)
    private var ok = false

    private val tts = TextToSpeech(ctx.applicationContext) { status ->
        ok = status == TextToSpeech.SUCCESS
        ready.countDown()
    }.apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
    }

    private fun awaitReady(): Boolean = ready.await(6, TimeUnit.SECONDS) && ok

    /** Vozes em portugues instaladas no aparelho. */
    fun voices(): List<VoiceOption> {
        if (!awaitReady()) return emptyList()
        return try {
            tts.voices.orEmpty()
                .filter { it.locale.language == "pt" }
                .sortedBy { it.name }
                .map { v ->
                    val rede = if (v.isNetworkConnectionRequired) " (rede)" else " (local)"
                    val qualidade = when {
                        v.quality >= 400 -> "alta"
                        v.quality >= 300 -> "media"
                        else -> "baixa"
                    }
                    VoiceOption(v.name, "${v.name}$rede  q=$qualidade")
                }
        } catch (e: Exception) {
            Log.w(TAG, "nao consegui listar vozes", e)
            emptyList()
        }
    }

    private fun applySettings(voiceId: String, rate: Float, pitch: Float) {
        tts.language = Locale("pt", "BR")
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        if (voiceId.isNotBlank()) {
            runCatching {
                tts.voices?.firstOrNull { it.name == voiceId }?.let { tts.voice = it }
            }
        }
    }

    /**
     * Sintetiza, filtra e toca. Sincrono: volta quando a frase acabou de sair.
     *
     * @return false se a sintese falhou e nada foi tocado.
     */
    fun speakFiltered(
        text: String,
        player: PcmPlayer,
        radioMix: Float,
        voiceId: String = "",
        rate: Float = 1.0f,
        pitch: Float = 1.0f
    ): Boolean {
        val pcm = renderFiltered(text, player.sampleRate, radioMix, voiceId, rate, pitch)
            ?: return false
        player.start()
        player.write(pcm)
        return true
    }

    /**
     * Sintetiza e filtra, mas NAO toca: devolve o PCM pronto.
     *
     * Essa separacao existe para o servico poder sintetizar a proxima frase
     * enquanto a anterior ainda esta tocando. Sem ela, o motor de TTS fica
     * ocioso durante toda a reproducao e as frases saem com buraco entre elas.
     */
    fun renderFiltered(
        text: String,
        sampleRate: Int,
        radioMix: Float,
        voiceId: String = "",
        rate: Float = 1.0f,
        pitch: Float = 1.0f
    ): ShortArray? {
        if (text.isBlank()) return ShortArray(0)
        if (!awaitReady()) return null
        applySettings(voiceId, rate, pitch)

        val out = File(ctx.cacheDir, "pedec-tts-${System.nanoTime()}.wav")
        val done = CountDownLatch(1)
        var failed = false

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = done.countDown()
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                failed = true
                done.countDown()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                failed = true
                done.countDown()
            }
        })

        val id = "pedec-${System.nanoTime()}"
        val params = Bundle()
        val queued = tts.synthesizeToFile(text, params, out, id)
        if (queued != TextToSpeech.SUCCESS) {
            out.delete()
            return null
        }

        // Sintese local e rapida; o teto e so para nao travar a sessao.
        if (!done.await(30, TimeUnit.SECONDS) || failed || !out.isFile || out.length() < 64) {
            out.delete()
            return null
        }

        return try {
            val pcm = SfxBank.decodeWav(out.readBytes())
            RadioFilter(sampleRate, radioMix).process(pcm)
            pcm
        } catch (e: Exception) {
            Log.w(TAG, "falha ao filtrar a voz local", e)
            null
        } finally {
            out.delete()
        }
    }

    /** Ultimo recurso: fala direto pelo sistema, sem filtro. */
    fun speakPlain(text: String) {
        if (!awaitReady()) return
        tts.language = Locale("pt", "BR")
        val done = CountDownLatch(1)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = done.countDown()
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) = done.countDown()
        })
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "pedec-plain")
        done.await(60, TimeUnit.SECONDS)
    }

    fun stop() { runCatching { tts.stop() } }

    fun release() {
        runCatching { tts.stop(); tts.shutdown() }
    }

    private companion object { const val TAG = "PedecSystemTts" }
}
