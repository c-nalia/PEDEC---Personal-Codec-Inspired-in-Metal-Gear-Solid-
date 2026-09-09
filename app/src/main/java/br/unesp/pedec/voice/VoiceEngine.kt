package br.unesp.pedec.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

/**
 * Toda a voz de entrada em um lugar so: microfone, deteccao de voz e Vosk.
 *
 * ## Por que este arquivo foi reescrito
 *
 * A versao anterior tinha oito variaveis de estado — mode, muted, feeding,
 * paused, pendingReset, sawSpeech, askStartedAt, lastVoiceAt — e DUAS threads
 * escrevendo nelas: a de audio e a do servico. Cada correcao pontual criava uma
 * corrida nova em outro lugar. O ultimo sintoma foi o mais claro: a palavra de
 * ativacao era reconhecida, mas a pergunta seguinte morria em "nao entendi".
 *
 * A causa: o servico pedia o reset do reconhecedor por uma bandeira, e a thread
 * de audio a atendia alguns quadros DEPOIS, ja com o inicio da pergunta
 * decodificado. O reset apagava exatamente esse comeco. Como nada mais chegava,
 * o contador de "ninguem falou" estourava e vinha o som de negacao.
 *
 * ## A regra agora
 *
 * Estado nenhum e compartilhado. Quem esta de fora nao escreve em campo algum:
 * envia um COMANDO para uma fila. A thread de audio tira da fila entre quadros
 * e aplica de uma vez — mudanca de modo, limpeza de buffer e reset do
 * reconhecedor acontecem juntos e na ordem em que foram pedidos, nunca partidos
 * pela metade.
 *
 * Isso tambem resolve o crash: o Recognizer do Vosk e um ponteiro para um
 * objeto C e nao aceita uso concorrente. Com a fila, so a thread de audio
 * encosta nele.
 *
 * Tudo offline. Sem chave, sem conta, sem rede.
 */
class VoiceEngine(
    private val ctx: Context,
    private val modelPath: String,
    initialPhrases: List<String> = WakeMatcher.DEFAULT_PHRASES,
    private val listener: Listener
) {

    /** Grafias aceitas para a ativacao; editavel sem reiniciar o motor. */
    @Volatile
    var phrases: List<String> = initialPhrases

    interface Listener {
        /** @param seed texto dito logo apos a palavra de ativacao, pode vir vazio. */
        fun onWake(seed: String)

        /**
         * Tudo que o reconhecedor entende enquanto espera a ativacao. Sem isso
         * voce nao tem como saber por que ele te ignorou: e este texto que diz
         * qual grafia acrescentar na lista.
         */
        fun onHeardWhileWaiting(text: String)
        fun onPartial(text: String)
        fun onQuestion(text: String)
        fun onQuestionTimeout()

        /**
         * Uma amostra de calibracao terminou. O texto e exatamente o que o
         * reconhecedor entendeu ao ouvir voce dizer a palavra de ativacao.
         * Vem vazio se nada foi captado.
         */
        fun onSample(text: String, pcm: ShortArray)

        fun onFailure(message: String)
    }

    enum class Mode {
        /** Esperando a palavra de ativacao. */
        WAKE,
        /** Capturando a pergunta. */
        ASK,
        /** Capturando uma amostra de calibracao. */
        TRAIN,
        /** Microfone aberto, audio descartado. */
        IDLE,
        /** Microfone fechado: outro dono vai usar. */
        PAUSED
    }

    /**
     * Ordens vindas de fora. Sao dados, nao acoes: quem envia nao executa nada,
     * so descreve o que quer. A thread de audio e que interpreta.
     */
    private sealed interface Cmd {
        object Wake : Cmd
        object Ask : Cmd
        object Train : Cmd
        object Idle : Cmd
        object Pause : Cmd
        object Resume : Cmd
        data class Mute(val on: Boolean) : Cmd
        data class Source(val value: Int) : Cmd
    }

    companion object {
        private const val TAG = "PedecVoice"
        private const val RATE = 16000
        /** 100 ms por quadro: responsivo o bastante e barato de processar. */
        private const val FRAME = 1600

        /**
         * Audio guardado ANTES de o detector de voz disparar. E o conserto do
         * bug mais grave que este motor teve: sem isso o primeiro fonema da
         * palavra se perde, porque plosiva surda (o /p/ de "Pedec") tem energia
         * baixa demais para acionar o portao. O reconhecedor recebia "edec".
         */
        private const val PREROLL_FRAMES = 4   // 400 ms

        private const val DEFAULT_END_SILENCE_MS = 900L
        private const val MAX_QUESTION_MS = 20_000L
        private const val NO_SPEECH_MS = 8_000L
        private const val HANGOVER_MS = 400L
        private const val WAKE_RESET_MS = 1500L
        private const val SAMPLE_CAP = 16000 * 12
        private const val TRAIN_END_SILENCE_MS = 700L
        private const val TRAIN_TIMEOUT_MS = 8_000L
    }

    // ------------------------------------------------------------- so leitura

    @Volatile var framesRead: Long = 0L
        private set

    /** Nivel do ultimo quadro em dBFS. Negativo; -90 e silencio digital. */
    @Volatile var lastLevelDb: Float = -90f
        private set

    @Volatile var lastVoiced: Boolean = false
        private set

    @Volatile var mode = Mode.WAKE
        private set

    // ------------------------------------------------- ajustes lidos a quente
    //
    // Estes podem ser @Volatile simples porque sao PARAMETROS, nao estado da
    // maquina: ler um valor velho por um quadro nao quebra nada.

    @Volatile var endSilenceMs: Long = DEFAULT_END_SILENCE_MS
    @Volatile var useAec: Boolean = false
    @Volatile var useNs: Boolean = false
    @Volatile var preferredSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION

    // ------------------------------------------------------------- a fila

    private val comandos = ConcurrentLinkedQueue<Cmd>()

    // --------------------------------------------------- estado da thread
    //
    // Daqui para baixo, TUDO pertence a thread de audio. Nenhum destes campos
    // e lido ou escrito de fora, e por isso nenhum precisa de @Volatile.

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var micSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private var needsRebuild = true
    private var emptyReads = 0

    private var mudo = false
    private var alimentando = false
    private val preRoll = ArrayDeque<ShortArray>()
    private val utterance = ShortArray(SAMPLE_CAP)
    private var utteranceLen = 0

    private var noiseFloor = 300.0
    private var lastVoiceAt = 0L
    private var startedAt = 0L
    private var sawSpeech = false
    private var seed = ""
    private var lastHeard = ""

    // ------------------------------------------------------------------ ciclo

    fun start(): Boolean {
        if (running) return true
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val m = Model(modelPath)
            model = m
            recognizer = Recognizer(m, RATE.toFloat())
            micSource = preferredSource
            running = true
            thread = Thread({ loop() }, "pedec-voice").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "falha ao iniciar o motor de voz", e)
            listener.onFailure("motor de voz: ${e.message}")
            release()
            false
        }
    }

    /**
     * Encerra e espera. Se a thread nao sair no prazo, NAO limpamos daqui:
     * ela guarda referencias locais ao recognizer e ao AudioRecord, e liberar
     * por baixo dela derruba o processo dentro do codigo nativo. Ela mesma
     * limpa no finally do laco, porque running ja e false.
     */
    fun stop() {
        running = false
        val t = thread
        thread = null
        t?.join(2000)
        if (t != null && t.isAlive) {
            Log.w(TAG, "thread de audio ainda viva; ela mesma vai limpar")
            return
        }
        release()
    }

    // ------------------------------------------------------- API do servico
    //
    // Todos os metodos abaixo apenas enfileiram. Nenhum toca em estado, o que
    // significa que a ordem das chamadas do servico e respeitada exatamente, e
    // que nenhuma delas pode pegar a thread de audio no meio de um quadro.

    /** Volta a esperar pela palavra de ativacao. */
    fun listenForWake() { comandos.add(Cmd.Wake) }

    /**
     * Passa a capturar a pergunta.
     *
     * Ja desmuta por conta propria. Na versao anterior o servico precisava
     * chamar `muted = false` logo depois, e a janela entre as duas chamadas
     * era corrida: a thread de audio rodava no meio e reescrevia o relogio de
     * silencio que este comando tinha acabado de zerar.
     */
    fun listenForQuestion() { comandos.add(Cmd.Ask) }

    /** Captura UMA elocucao para calibracao e para. */
    fun captureSample() { comandos.add(Cmd.Train) }

    /** Para de processar sem soltar o microfone. */
    fun idle() { comandos.add(Cmd.Idle) }

    /** Fecha o microfone: outro dono vai usar (o reconhecedor do Android). */
    fun pauseCapture() { comandos.add(Cmd.Pause) }

    /** Reabre o microfone. Chame listenForWake/listenForQuestion em seguida. */
    fun resumeCapture() { comandos.add(Cmd.Resume) }

    /** Descarta o audio sem mudar de modo. Usado enquanto o app fala. */
    var muted: Boolean
        get() = mudo
        set(v) { comandos.add(Cmd.Mute(v)) }

    /**
     * Troca a fonte do microfone. Em espera usamos o microfone do aparelho;
     * durante a chamada, o do fone, ja que o canal SCO so existe nesse momento.
     */
    fun useHeadsetMic(on: Boolean) {
        val alvo = if (on) MediaRecorder.AudioSource.VOICE_COMMUNICATION else preferredSource
        comandos.add(Cmd.Source(alvo))
    }

    // -------------------------------------------------------------- o laco

    private fun loop() {
        val frame = ShortArray(FRAME)
        try {
            while (running) {
                aplicarComandos()

                if (mode == Mode.PAUSED) {
                    if (record != null) closeRecord()
                    Thread.sleep(60)
                    continue
                }

                if (needsRebuild || record == null) {
                    needsRebuild = false
                    closeRecord()
                    if (!openRecord()) {
                        // Pode ser o microfone ainda ocupado pelo reconhecedor
                        // do Android saindo de cena. Tenta de novo em vez de
                        // derrubar o motor.
                        Thread.sleep(200)
                        continue
                    }
                }

                val rec = record ?: continue
                val n = try {
                    rec.read(frame, 0, frame.size)
                } catch (e: IllegalStateException) {
                    needsRebuild = true
                    continue
                }

                if (n <= 0) {
                    if (++emptyReads > 50) {   // ~1 segundo sem nenhum audio
                        Log.w(TAG, "microfone mudo; reabrindo")
                        emptyReads = 0
                        needsRebuild = true
                    }
                    Thread.sleep(20)
                    continue
                }
                emptyReads = 0
                framesRead++

                val level = rms(frame, n)
                val voiced = updateVad(level)
                lastLevelDb = if (level < 1.0) -90f
                    else (20.0 * kotlin.math.log10(level / 32768.0)).toFloat()
                lastVoiced = voiced

                if (mudo) {
                    // Descartar audio e melhor do que fechar o microfone:
                    // manter o AudioRecord vivo evita reabrir o canal SCO a
                    // cada turno. Mas NAO tocamos no relogio de silencio aqui —
                    // era isso que estragava o inicio da pergunta.
                    limparElocucao()
                    continue
                }

                if (voiced) lastVoiceAt = System.currentTimeMillis()
                val now = System.currentTimeMillis()

                // Rede de seguranca por quadro. Isto roda numa Thread crua:
                // excecao nao tratada aqui nao vira erro na tela, vai para o
                // tratador padrao e MATA O PROCESSO. E o caminho passa pelo
                // Vosk (nativo) e pelos callbacks, que entram no servico
                // inteiro. Continuar o laco e melhor que um motor mudo.
                try {
                    when (mode) {
                        Mode.WAKE -> naEspera(frame, n, voiced, now)
                        Mode.ASK -> naPergunta(frame, n, now)
                        Mode.TRAIN -> naAmostra(frame, n, voiced, now)
                        Mode.IDLE, Mode.PAUSED -> Unit
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "erro ao processar audio; o laco continua", e)
                    limparElocucao()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "thread de audio caiu", e)
        } finally {
            // A limpeza acontece AQUI, na dona do recognizer. O modelo do Vosk
            // ocupa dezenas de megabytes: se a thread saisse sem liberar, cada
            // reinicio somaria mais um na memoria ate o sistema matar o app.
            runCatching { release() }
        }
    }

    /**
     * Aplica tudo que chegou desde o quadro anterior.
     *
     * Cada comando e uma transicao COMPLETA: modo, buffers, relogios e o reset
     * do reconhecedor mudam juntos. Nao existe mais o estado intermediario em
     * que o modo ja mudou mas o reset ainda nao veio — que era exatamente onde
     * o inicio da pergunta se perdia.
     */
    private fun aplicarComandos() {
        while (true) {
            val cmd = comandos.poll() ?: return
            when (cmd) {
                is Cmd.Wake -> {
                    mode = Mode.WAKE
                    mudo = false
                    seed = ""
                    lastHeard = ""
                    sawSpeech = false
                    lastVoiceAt = System.currentTimeMillis()
                    limparElocucao()
                    resetReconhecedor()
                }
                is Cmd.Ask -> {
                    mode = Mode.ASK
                    mudo = false
                    sawSpeech = false
                    startedAt = System.currentTimeMillis()
                    lastVoiceAt = 0L
                    alimentando = true
                    limparElocucao()
                    resetReconhecedor()
                }
                is Cmd.Train -> {
                    mode = Mode.TRAIN
                    mudo = false
                    sawSpeech = false
                    startedAt = System.currentTimeMillis()
                    lastVoiceAt = 0L
                    alimentando = false
                    limparElocucao()
                    resetReconhecedor()
                }
                is Cmd.Idle -> {
                    mode = Mode.IDLE
                    mudo = true
                    limparElocucao()
                    resetReconhecedor()
                }
                is Cmd.Pause -> {
                    mode = Mode.PAUSED
                    mudo = true
                    limparElocucao()
                }
                is Cmd.Resume -> {
                    // Volta para IDLE, nao para WAKE: quem decide o proximo
                    // modo e o servico, com o comando seguinte. Assumir WAKE
                    // aqui faria a escuta disparar durante o bipe.
                    if (mode == Mode.PAUSED) mode = Mode.IDLE
                    needsRebuild = true
                    emptyReads = 0
                }
                is Cmd.Mute -> {
                    mudo = cmd.on
                    if (cmd.on) limparElocucao()
                }
                is Cmd.Source -> {
                    if (cmd.value != micSource) {
                        micSource = cmd.value
                        needsRebuild = true
                    }
                }
            }
        }
    }

    private fun limparElocucao() {
        preRoll.clear()
        alimentando = false
        utteranceLen = 0
    }

    private fun resetReconhecedor() {
        runCatching { recognizer?.reset() }
    }

    // ------------------------------------------------------------- estados

    private fun naEspera(frame: ShortArray, n: Int, voiced: Boolean, now: Long) {
        val recente = now - lastVoiceAt < HANGOVER_MS
        if (!voiced && !recente) {
            // Portao de energia: em silencio nao gastamos CPU decodificando.
            // Mas guardamos o audio num anel curto, porque quando a voz
            // finalmente dispara o portao o comeco da palavra ja passou.
            preRoll.addLast(frame.copyOf(n))
            while (preRoll.size > PREROLL_FRAMES) preRoll.removeFirst()
            alimentando = false

            if (now - lastVoiceAt > WAKE_RESET_MS) {
                resetReconhecedor()
                lastVoiceAt = now - WAKE_RESET_MS
                if (lastHeard.isNotEmpty()) {
                    lastHeard = ""
                    listener.onHeardWhileWaiting("")
                }
            }
            return
        }

        val rec = recognizer ?: return

        if (!alimentando) {
            alimentando = true
            // Despeja a pre-captura: aqui esta o inicio da palavra.
            preRoll.forEach { runCatching { rec.acceptWaveForm(it, it.size) } }
            preRoll.clear()
        }

        val texto = if (rec.acceptWaveForm(frame, n)) {
            json(rec.result, "text")
        } else {
            json(rec.partialResult, "partial")
        }
        if (texto.isBlank()) return

        // Mostra no HUD o que ele esta entendendo. E o unico jeito de descobrir
        // qual grafia acrescentar na lista de ativacao.
        if (texto != lastHeard) {
            lastHeard = texto
            listener.onHeardWhileWaiting(texto)
        }

        val achou = WakeMatcher.find(texto, phrases) ?: return

        // Sai do modo de espera AQUI, antes de avisar o servico. Se
        // esperassemos o servico reagir, os dois segundos de bipe cairiam no
        // decodificador e disparariam a ativacao de novo.
        //
        // Vai para IDLE e mudo, nao direto para ASK: o servico ainda vai tocar
        // o bipe de linha aberta. Ele manda listenForQuestion() quando o bipe
        // acabar, e so entao a captura da pergunta comeca — do zero, sem o
        // bipe dentro.
        resetReconhecedor()
        seed = achou.rest
        lastHeard = ""
        mode = Mode.IDLE
        mudo = true
        limparElocucao()
        listener.onWake(seed)
    }

    private fun naPergunta(frame: ShortArray, n: Int, now: Long) {
        val rec = recognizer ?: return
        val completo = rec.acceptWaveForm(frame, n)

        if (completo) {
            val texto = json(rec.result, "text")
            if (texto.isNotBlank()) {
                sawSpeech = true
                fecharPergunta(texto)
                return
            }
        } else {
            val parcial = json(rec.partialResult, "partial")
            if (parcial.isNotBlank()) {
                sawSpeech = true
                listener.onPartial(juntar(seed, parcial))
            }
        }

        val silencio = now - lastVoiceAt
        if (sawSpeech && lastVoiceAt > 0 && silencio > endSilenceMs) {
            fecharPergunta(json(rec.finalResult, "text"))
            return
        }
        if (!sawSpeech && now - startedAt > NO_SPEECH_MS) {
            // Ninguem falou. O seed pode ter vindo junto com a ativacao
            // ("Pedec, que horas sao") — nesse caso ja temos a pergunta e seria
            // absurdo responder com negacao.
            if (seed.isNotBlank()) {
                fecharPergunta("")
            } else {
                resetReconhecedor()
                mode = Mode.IDLE
                mudo = true
                listener.onQuestionTimeout()
            }
            return
        }
        if (now - startedAt > MAX_QUESTION_MS) {
            fecharPergunta(json(rec.finalResult, "text"))
        }
    }

    /**
     * Mesma logica da pergunta, com duas diferencas: usa a pre-captura (a
     * palavra e curta, perder o primeiro fonema estragaria a calibracao) e
     * para ao fim de uma unica elocucao.
     */
    private fun naAmostra(frame: ShortArray, n: Int, voiced: Boolean, now: Long) {
        val rec = recognizer ?: return
        val recente = now - lastVoiceAt < HANGOVER_MS

        if (!sawSpeech && !voiced && !recente) {
            preRoll.addLast(frame.copyOf(n))
            while (preRoll.size > PREROLL_FRAMES) preRoll.removeFirst()
            if (now - startedAt > TRAIN_TIMEOUT_MS) fecharAmostra("")
            return
        }

        if (!alimentando) {
            alimentando = true
            preRoll.forEach { buf ->
                runCatching { rec.acceptWaveForm(buf, buf.size) }
                // A pre-captura tambem entra na amostra: o comeco da palavra e
                // justamente o que distingue uma da outra.
                acumular(buf, buf.size)
            }
            preRoll.clear()
        }

        acumular(frame, n)
        val completo = rec.acceptWaveForm(frame, n)
        if (voiced) sawSpeech = true

        if (completo) {
            val texto = json(rec.result, "text")
            if (texto.isNotBlank()) {
                fecharAmostra(texto)
                return
            }
        } else {
            val parcial = json(rec.partialResult, "partial")
            if (parcial.isNotBlank()) {
                sawSpeech = true
                listener.onHeardWhileWaiting(parcial)
            }
        }

        if (sawSpeech && lastVoiceAt > 0 && now - lastVoiceAt > TRAIN_END_SILENCE_MS) {
            fecharAmostra(json(rec.finalResult, "text"))
        } else if (now - startedAt > TRAIN_TIMEOUT_MS) {
            fecharAmostra(json(rec.finalResult, "text"))
        }
    }

    private fun fecharPergunta(cauda: String) {
        resetReconhecedor()
        val completo = juntar(seed, cauda)
        seed = ""
        // Sai de ASK antes de avisar: o servico vai comecar a falar, e um
        // quadro processado no meio disso reabriria a captura sozinho.
        mode = Mode.IDLE
        mudo = true
        limparElocucao()
        if (completo.isBlank()) listener.onQuestionTimeout() else listener.onQuestion(completo)
    }

    private fun fecharAmostra(texto: String) {
        resetReconhecedor()
        val pcm = utterance.copyOf(utteranceLen)
        mode = Mode.IDLE
        mudo = true
        limparElocucao()
        listener.onSample(texto.trim(), pcm)
    }

    private fun acumular(buf: ShortArray, n: Int) {
        val espaco = SAMPLE_CAP - utteranceLen
        if (espaco <= 0) return
        val leva = minOf(n, espaco)
        System.arraycopy(buf, 0, utterance, utteranceLen, leva)
        utteranceLen += leva
    }

    private fun juntar(a: String, b: String): String =
        listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    // -------------------------------------------------------------------- vad

    private fun rms(buf: ShortArray, n: Int): Double {
        var acc = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            acc += v * v
        }
        return sqrt(acc / n)
    }

    /**
     * Piso de ruido adaptativo: sobe devagar, desce rapido. Assim o portao se
     * ajusta sozinho entre um quarto silencioso e a rua.
     */
    private fun updateVad(level: Double): Boolean {
        noiseFloor = if (level < noiseFloor) {
            noiseFloor * 0.9 + level * 0.1
        } else {
            noiseFloor * 0.995 + level * 0.005
        }
        noiseFloor = noiseFloor.coerceIn(80.0, 4000.0)
        return level > noiseFloor * 2.5 + 150
    }

    private fun json(raw: String?, field: String): String =
        runCatching { JSONObject(raw ?: "{}").optString(field) }.getOrDefault("").trim()

    // ----------------------------------------------------------------- microfone

    @SuppressLint("MissingPermission")
    private fun openRecord(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val size = maxOf(minBuf * 2, FRAME * 8)
        return try {
            val r = AudioRecord(
                micSource, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, size
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { r.release() }
                return false
            }
            if (useAec && AcousticEchoCanceler.isAvailable()) {
                aec = runCatching {
                    AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
                }.getOrNull()
            }
            if (useNs && NoiseSuppressor.isAvailable()) {
                ns = runCatching {
                    NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
                }.getOrNull()
            }
            r.startRecording()
            // O construtor pode devolver INITIALIZED e o startRecording falhar
            // mesmo assim, quando outro dono do microfone ainda nao soltou o
            // dispositivo. Sem conferir isto, o motor fica lendo zero bytes
            // para sempre: nenhum erro, nenhuma escuta.
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "startRecording nao pegou; vai tentar de novo")
                runCatching { r.release() }
                return false
            }
            record = r
            noiseFloor = 300.0
            emptyReads = 0
            Log.i(TAG, "microfone aberto (fonte=$micSource)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "falha ao abrir o microfone", e)
            false
        }
    }

    private fun closeRecord() {
        runCatching { aec?.release() }; aec = null
        runCatching { ns?.release() }; ns = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    private fun release() {
        closeRecord()
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
    }
}
