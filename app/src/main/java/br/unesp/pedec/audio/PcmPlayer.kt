package br.unesp.pedec.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Saida de audio unica do app. Sempre USAGE_VOICE_COMMUNICATION para que o
 * Android empurre o som pelo canal SCO do fone de conducao ossea.
 */
class PcmPlayer(
    val sampleRate: Int = 22050,
    /**
     * USAGE_VOICE_COMMUNICATION empurra o som pelo canal de chamada (SCO);
     * USAGE_MEDIA sai pelo A2DP em qualidade cheia. Quem decide e o modo de
     * audio escolhido na configuracao.
     */
    private val usage: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION
) {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var framesWritten: Long = 0
    @Volatile var stopped = false
        private set

    /** Serializa escrita e liberacao entre a thread da fala e a da escuta. */
    private val lock = Any()

    private val minBuf = AudioTrack.getMinBufferSize(
        sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(4096)

    /**
     * O corpo INTEIRO fica dentro do lock, e isso importa.
     *
     * Antes, so a checagem `if (track != null) return` era sincronizada; a
     * construcao e o `track = t` ficavam de fora. Duas threads chamando start()
     * ao mesmo tempo — e sao duas mesmo: os efeitos sonoros e a fala da resposta
     * — passavam as duas pela checagem e construiam dois AudioTrack. Um era
     * sobrescrito e NUNCA liberado.
     *
     * O Android limita quantos AudioTrack existem por processo. Vazando um por
     * chamada, em algumas dezenas de usos o build() passa a lancar e o app cai
     * ao tentar falar — bem longe, no tempo, do vazamento que causou tudo.
     */
    fun start() {
        synchronized(lock) {
            if (track != null) return
            stopped = false
            framesWritten = 0
            val t = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                // Sem saida de audio o codec fica mudo, mas o app continua de pe.
                // Melhor uma resposta que voce le no HUD do que um crash.
                Log.e(TAG, "nao foi possivel abrir a saida de audio", e)
                return
            }
            runCatching { t.play() }.onFailure {
                Log.e(TAG, "play() falhou", it)
                runCatching { t.release() }
                return
            }
            track = t
        }
    }

    /**
     * Escrita bloqueante: o proprio AudioTrack faz o compasso da reproducao.
     *
     * Sincronizado porque a interrupcao vem de OUTRA thread — a do motor de
     * voz, quando voce fala a palavra de ativacao durante a resposta. Sem o
     * lock, o release() podia acontecer no meio de um write() e derrubava o
     * processo no codigo nativo, sem excecao Java para capturar.
     */
    fun write(data: ShortArray, len: Int = data.size) {
        synchronized(lock) {
            if (stopped) return
            val t = track ?: return
            var off = 0
            while (off < len && !stopped) {
                val n = try {
                    t.write(data, off, len - off)
                } catch (e: IllegalStateException) {
                    return
                }
                if (n <= 0) break
                off += n
            }
            framesWritten += off
        }
    }

    /** Escreve e espera terminar de tocar. Usado nos bipes, para nao vazarem no microfone. */
    fun playAndWait(data: ShortArray) {
        start()
        write(data)
        waitUntilDrained()
    }

    /**
     * Bloqueia ate a fila esvaziar, com teto ligado ao audio que resta.
     *
     * O teto era de 120 segundos, e isso derrubava o app inteiro: basta o
     * playbackHeadPosition parar de avancar — pause, flush, troca de rota,
     * underrun — para este laco segurar dois minutos. Como ele roda no finally
     * da sessao, ANTES de religar a escuta, o sintoma era "depois de desligar
     * ele nao ouve mais nada". Nao era nunca; era dois minutos.
     */
    fun waitUntilDrained() {
        val t = track ?: return
        try {
            // Esta primeira leitura estava FORA do try. Se outra thread tivesse
            // liberado o AudioTrack no intervalo, playbackHeadPosition lancava
            // IllegalStateException aqui e ninguem pegava — crash na hora de
            // encerrar a fala, que e exatamente quando as duas threads se
            // encontram.
            val pendingMs = ((framesWritten - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL))
                .coerceAtLeast(0) * 1000 / sampleRate)
            // O que falta tocar, mais meio segundo de folga, e no maximo 5 s.
            val budget = (pendingMs + 500).coerceIn(200, 5_000)
            val deadline = System.currentTimeMillis() + budget
            while (!stopped && System.currentTimeMillis() < deadline) {
                // Reconfere a cada volta: o dono pode ter liberado no meio.
                if (track == null) return
                val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (head >= framesWritten) break
                val remainingMs = (framesWritten - head) * 1000 / sampleRate
                Thread.sleep(remainingMs.coerceIn(10, 200))
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
        }
    }

    fun finish() {
        waitUntilDrained()
        release()
    }

    /**
     * Cala a saida imediatamente. NAO libera o AudioTrack: quem libera e o dono
     * da sessao, via finish(), depois que todas as threads que escrevem ja
     * terminaram. Liberar aqui era a origem do travamento na interrupcao.
     */
    fun stopNow() {
        stopped = true
        synchronized(lock) {
            try { track?.pause(); track?.flush() } catch (_: Exception) {}
            // O flush descarta o que estava na fila, entao a cabeca de leitura
            // nunca alcancaria framesWritten. Zerar os dois evita que um dreno
            // posterior fique esperando audio que ja foi jogado fora.
            framesWritten = 0
        }
    }

    private fun release() {
        synchronized(lock) {
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            track = null
            framesWritten = 0
        }
    }

    private companion object {
        const val TAG = "PedecPlayer"
    }
}
