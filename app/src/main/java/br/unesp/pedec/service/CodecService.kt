package br.unesp.pedec.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.media.AudioAttributes
import br.unesp.pedec.audio.AudioMode
import br.unesp.pedec.audio.AudioRoute
import br.unesp.pedec.audio.CodecSfx
import br.unesp.pedec.audio.PcmPlayer
import br.unesp.pedec.audio.Sfx
import br.unesp.pedec.audio.SfxBank
import br.unesp.pedec.audio.UiSfx
import br.unesp.pedec.data.Memoria
import br.unesp.pedec.data.Prefs
import br.unesp.pedec.llm.ClaudeClient
import br.unesp.pedec.llm.Conversation
import br.unesp.pedec.llm.LlmClient
import br.unesp.pedec.llm.OpenAiClient
import br.unesp.pedec.music.MusicBridge
import br.unesp.pedec.music.MusicCommands
import br.unesp.pedec.stt.AndroidStt
import br.unesp.pedec.stt.SttEngine
import br.unesp.pedec.tts.ElevenLabsTts
import br.unesp.pedec.tts.SherpaTts
import br.unesp.pedec.tts.SentenceChunker
import br.unesp.pedec.tts.SystemTts
import br.unesp.pedec.tts.TtsEngine
import br.unesp.pedec.ui.MainActivity
import br.unesp.pedec.voice.VoiceCommands
import br.unesp.pedec.voice.VoiceEngine
import br.unesp.pedec.voice.VoskModel
import br.unesp.pedec.voice.WakeMatcher
import br.unesp.pedec.web.WebSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Servico em primeiro plano que segura a chamada de codec inteira:
 * palavra de ativacao -> bipe -> pergunta -> Claude -> voz.
 *
 * A entrada de audio e responsabilidade do VoiceEngine, que mantem um unico
 * microfone aberto para tudo. O servico so diz em que modo ele deve estar.
 */
class CodecService : Service() {

    companion object {
        const val ACTION_START = "br.unesp.pedec.START"
        const val ACTION_STOP = "br.unesp.pedec.STOP"
        const val ACTION_TRIGGER = "br.unesp.pedec.TRIGGER"
        const val ACTION_TRAIN_CAPTURE = "br.unesp.pedec.TRAIN_CAPTURE"
        const val ACTION_TRAIN_END = "br.unesp.pedec.TRAIN_END"
        const val ACTION_RELOAD = "br.unesp.pedec.RELOAD"
        const val ACTION_MIC_TEST = "br.unesp.pedec.MIC_TEST"
        private const val CHANNEL_ID = "pedec_codec"
        private const val NOTIF_ID = 8514
        private const val TAG = "PedecService"

        fun start(ctx: Context) {
            val i = Intent(ctx, CodecService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CodecService::class.java).setAction(ACTION_STOP))
        }

        /** Abre a chamada manualmente, sem falar "Pedec". */
        fun trigger(ctx: Context) {
            val i = Intent(ctx, CodecService::class.java).setAction(ACTION_TRIGGER)
            ContextCompat.startForegroundService(ctx, i)
        }

        /** Grava uma amostra de calibracao da palavra de ativacao. */
        fun trainCapture(ctx: Context) = send(ctx, ACTION_TRAIN_CAPTURE)

        /** Encerra a calibracao e volta a esperar a ativacao. */
        fun trainEnd(ctx: Context) = send(ctx, ACTION_TRAIN_END)

        /** Reaplica as preferencias sem reiniciar o servico. */
        fun reload(ctx: Context) = send(ctx, ACTION_RELOAD)

        /** Grava uma elocucao e toca de volta, crua. Diagnostico do microfone. */
        fun micTest(ctx: Context) = send(ctx, ACTION_MIC_TEST)

        private fun send(ctx: Context, action: String) {
            val i = Intent(ctx, CodecService::class.java).setAction(action)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var route: AudioRoute

    private var engine: VoiceEngine? = null
    private var systemTts: SystemTts? = null
    private var sherpaTts: SherpaTts? = null
    private var androidStt: AndroidStt? = null
    private val music by lazy { MusicBridge(this) }
    private val memoria by lazy { Memoria.get(this) }
    private var sessionJob: Job? = null
    private var bootJob: Job? = null
    private val conversation = Conversation()

    /** Turno de escuta em aberto, resolvido pelos callbacks do motor de voz. */
    @Volatile private var pendingQuestion: CompletableDeferred<String?>? = null

    /** Player da sessao atual, para a interrupcao conseguir calar a fala. */
    @Volatile private var livePlayer: PcmPlayer? = null

    /** Ligado quando voce corta a resposta dizendo a palavra de ativacao. */
    @Volatile private var interrupted = false

    /** true enquanto a captura em curso e o teste de microfone. */
    @Volatile private var micTesting = false

    private var watchdog: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** false se o onCreate nao completou. Evita crash em cascata no onDestroy. */
    @Volatile private var criado = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        route = AudioRoute(this)
        UiSfx.init(this)
        UiSfx.volume = prefs.sfxVolume
        createChannel()
        criado = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ------------------------------------------------------------------
        // ISTO ERA UM CRASH GARANTIDO NO ANDROID 14+.
        //
        // Este servico e declarado com foregroundServiceType="microphone". A
        // partir do Android 14 o sistema recusa iniciar um servico desse tipo
        // em duas situacoes, e recusa LANCANDO, nao devolvendo erro:
        //
        //   - sem RECORD_AUDIO concedido -> SecurityException
        //   - iniciado com o app em segundo plano (o nosso BootReceiver!)
        //     -> ForegroundServiceStartNotAllowedException
        //
        // O try/catch do BootReceiver nao cobria isso: quem lanca e o
        // startForeground DAQUI DENTRO, ja em outro ponto de execucao, fora do
        // alcance daquele catch.
        //
        // Agora a permissao e conferida antes, e a chamada e protegida. Sem
        // permissao o servico apenas encerra e avisa a interface.
        // ------------------------------------------------------------------
        if (!hasMicPermission()) {
            Log.w(TAG, "sem RECORD_AUDIO: o servico nao pode subir")
            CodecBus.error("permissao de microfone negada")
            CodecBus.update { it.copy(phase = Phase.OFF, engine = "sem permissao") }
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIF_ID, buildNotification("em escuta pela palavra de ativacao"))
        } catch (e: Exception) {
            Log.e(TAG, "o sistema recusou o servico em primeiro plano", e)
            CodecBus.error("o sistema bloqueou a escuta; abra o app e ligue de novo")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER -> {
                ensureEngine()
                openSession("")
            }
            ACTION_TRAIN_CAPTURE -> {
                CodecBus.capturing(true)
                scope.launch {
                    // Se voce abriu a calibracao sem ter ligado a escuta, o
                    // motor ainda nem existe: ensureEngine() so DISPARA o
                    // carregamento, que leva segundos. Chamar captureSample()
                    // logo em seguida caia num engine nulo e o botao nao fazia
                    // absolutamente nada, sem erro nenhum na tela.
                    //
                    // Uma sessao em andamento tambem roubaria o microfone, e o
                    // finally dela chama listenForWake() — que desfaria o modo
                    // de treino se rodasse depois. Por isso: cancela, espera
                    // terminar, e so entao entra em treino.
                    // runCatching engoliria tambem o cancelamento DESTA
                    // corrotina, nao so a falha de encerrar a outra.
                    try {
                        sessionJob?.cancelAndJoin()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    ensureEngine()
                    bootJob?.join()

                    val e = engine
                    if (e == null) {
                        CodecBus.error("motor de voz nao carregou")
                        CodecBus.capturing(false)
                    } else {
                        e.resumeCapture()
                        e.captureSample()
                    }
                }
            }
            ACTION_TRAIN_END -> {
                applyPrefs()
                engine?.resumeCapture()
                engine?.listenForWake()
                CodecBus.capturing(false)
            }
            ACTION_MIC_TEST -> {
                CodecBus.capturing(true)
                micTesting = true
                scope.launch {
                    try { sessionJob?.cancelAndJoin() } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                    ensureEngine()
                    bootJob?.join()
                    val e = engine
                    if (e == null) {
                        CodecBus.error("motor de voz nao carregou")
                        CodecBus.capturing(false)
                        micTesting = false
                    } else {
                        e.resumeCapture()
                        e.captureSample()
                    }
                }
            }
            ACTION_RELOAD -> applyPrefs()
            else -> ensureEngine()
        }
        return START_STICKY
    }

    // ------------------------------------------------------------- motor de voz

    private fun ensureEngine() {
        if (engine != null || bootJob?.isActive == true) return
        if (!hasMicPermission()) {
            CodecBus.error("permissao de microfone negada")
            return
        }

        CodecBus.update { it.copy(engine = "carregando o modelo...") }

        bootJob = scope.launch {
            // A primeira execucao copia o modelo de assets para o disco:
            // sao alguns segundos e uns 40 MB, uma vez so.
            when (val status = VoskModel.ensure(this@CodecService)) {
                is VoskModel.Status.Missing -> {
                    Log.e(TAG, status.message)
                    CodecBus.error(status.message)
                    CodecBus.update { it.copy(engine = "modelo ausente") }
                }
                is VoskModel.Status.Ready -> {
                    val e = VoiceEngine(
                        ctx = this@CodecService,
                        modelPath = status.path,
                        initialPhrases = WakeMatcher.parsePhrases(prefs.wakePhrases),
                        listener = voiceListener
                    )
                    e.endSilenceMs = prefs.endSilenceMs.toLong()
                    e.useAec = prefs.useAec
                    e.useNs = prefs.useNs
                    e.preferredSource = prefs.micSource
                    if (e.start()) {
                        engine = e
                        e.listenForWake()
                        startWatchdog()
                        CodecBus.update {
                            it.copy(
                                phase = Phase.STANDBY,
                                headset = route.hasHeadset(),
                                engine = "vosk (" + status.layout.name.lowercase() + ")"
                            )
                        }
                    } else {
                        CodecBus.update { it.copy(engine = "falhou ao iniciar") }
                    }
                }
            }
        }
    }

    private val voiceListener = object : VoiceEngine.Listener {
        override fun onWake(seed: String) {
            Log.i(TAG, "ativacao detectada, sobra=\"$seed\"")
            // Alerta imediato pelo SoundPool: sai no mesmo instante da deteccao
            // e toca por cima da negociacao do canal SCO, sem custar latencia.
            UiSfx.play(Sfx.ALERT)

            // Com a chamada aberta, dizer a palavra de novo nao abre outra
            // sessao: corta a fala em andamento. E o "chega, me escuta".
            if (sessionJob?.isActive == true) {
                Log.i(TAG, "interrompido pela palavra de ativacao")
                interrupted = true
                livePlayer?.stopNow()
                return
            }
            openSession(seed)
        }

        override fun onPartial(text: String) {
            if (pendingQuestion != null) CodecBus.transcript(text)
        }

        override fun onHeardWhileWaiting(text: String) {
            CodecBus.update { it.copy(heard = text) }
        }

        override fun onQuestion(text: String) {
            pendingQuestion?.complete(text)
        }

        override fun onQuestionTimeout() {
            pendingQuestion?.complete(null)
        }

        override fun onSample(text: String, pcm: ShortArray) {
            Log.i(TAG, "amostra: \"$text\" (${pcm.size / 16} ms)")

            if (micTesting) {
                micTesting = false
                scope.launch { playBackRaw(pcm, text) }
                return
            }
            CodecBus.addSample(text)
        }

        override fun onFailure(message: String) {
            Log.e(TAG, "motor de voz: $message")
            CodecBus.error(message)
        }
    }

    /**
     * Vigia a captura. Se o contador de quadros parar de crescer com a escuta
     * ligada e sem sessao em curso, o microfone morreu de um jeito que nao
     * lanca excecao — dispositivo tomado por outro app, canal SCO caindo,
     * startRecording que falhou em silencio. Reiniciar e a unica saida, e sem
     * isto o app so parecia surdo.
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            var last = -1L
            var strikes = 0
            while (true) {
                delay(400)
                val e = engine ?: continue
                if (sessionJob?.isActive == true) {
                    last = e.framesRead
                    strikes = 0
                    continue
                }
                CodecBus.mic(e.lastLevelDb, e.lastVoiced)
                val now = e.framesRead
                if (now == last) {
                    strikes++
                    if (strikes >= 20) {
                        Log.e(TAG, "reiniciando o motor de voz")
                        CodecBus.update { it.copy(engine = "reiniciando a escuta...") }
                        runCatching { e.stop() }
                        engine = null
                        strikes = 0
                        last = -1L
                        ensureEngine()
                        return@launch
                    }
                } else {
                    strikes = 0
                }
                last = now
            }
        }
    }

    /**
     * Reaplica no motor de voz o que mudou na configuracao, sem reiniciar nada.
     * E o que faz a calibracao valer imediatamente depois de salva.
     */
    private fun applyPrefs() {
        engine?.let { e ->
            e.phrases = WakeMatcher.parsePhrases(prefs.wakePhrases)
            e.endSilenceMs = prefs.endSilenceMs.toLong()
            e.useAec = prefs.useAec
            e.useNs = prefs.useNs
            if (e.preferredSource != prefs.micSource) {
                e.preferredSource = prefs.micSource
                // Forca reabrir com a fonte nova, senao a troca so valeria na
                // proxima vez que algo reiniciasse a captura por outro motivo.
                e.useHeadsetMic(false)
                e.resumeCapture()
            }
        }
        UiSfx.volume = prefs.sfxVolume
    }

    /**
     * Monta o cliente do provedor configurado. A Anthropic tem formato proprio;
     * todo o resto fala o formato da OpenAI e usa a mesma implementacao.
     *
     * @return null se falta a chave.
     */
    private fun buildClient(): LlmClient? {
        val p = prefs.provider
        val key = prefs.key(p)
        if (p.needsKey && key.isBlank()) return null
        return if (p.isAnthropic) {
            ClaudeClient(apiKey = key, model = prefs.model(p), effort = prefs.effort)
        } else {
            OpenAiClient(
                baseUrl = prefs.baseUrl(p),
                apiKey = key,
                model = prefs.model(p),
                // O mesmo seletor de esforco vale aqui: nos modelos que
                // raciocinam antes de falar, e ele que define quanto tempo
                // voce espera em silencio.
                reasoningEffort = prefs.effort
            )
        }
    }

    /**
     * Toca de volta exatamente o que o microfone captou, sem filtro nenhum.
     *
     * E a prova direta que faltava. Se a sua voz voltar limpa, a captacao esta
     * boa e o problema e do detector. Se voltar muda ou picotada, o problema e
     * anterior a qualquer detector, e trocar de algoritmo nao resolveria.
     */
    private suspend fun playBackRaw(pcm: ShortArray, heard: String) {
        var pico = 0
        for (v in pcm) { val a = kotlin.math.abs(v.toInt()); if (a > pico) pico = a }
        val db = if (pico < 1) -90.0 else 20.0 * kotlin.math.log10(pico / 32768.0)

        CodecBus.update {
            it.copy(
                response = "gravado ${pcm.size / 16} ms, pico %.0f dB%s".format(
                    db,
                    if (heard.isNotBlank()) "\nvosk entendeu: $heard" else ""
                ),
                error = if (db < -55) {
                    "captacao fraca demais: troque a fonte para MIC e desligue eco e ruido"
                } else ""
            )
        }

        // Sai pela rota de midia, no volume do aparelho, sem passar pelo
        // filtro de radio: aqui o objetivo e ouvir o sinal como ele chegou.
        val player = PcmPlayer(16000, AudioAttributes.USAGE_MEDIA)
        try {
            player.start()
            player.write(pcm)
            player.waitUntilDrained()
        } finally {
            runCatching { player.finish() }
            CodecBus.capturing(false)
            engine?.resumeCapture()
            engine?.listenForWake()
        }
    }

    /** Efeito do banco, com o bipe sintetizado como rede de seguranca. */
    private fun sfx(s: Sfx, fallback: () -> ShortArray): ShortArray =
        SfxBank.render(this, s, prefs.sfxFull, prefs.sfxVolume) ?: fallback()

    // ------------------------------------------------------------------ sessao

    private fun openSession(seed: String) {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch { runSession(seed) }
    }

    private suspend fun runSession(seed: String) {
        val voice = engine
        voice?.muted = true

        // Decide o perfil de audio antes de abrir qualquer coisa. No modo de
        // chamada o engage() ainda pode falhar: alguns fones anunciam suporte
        // e recusam na hora. Nesse caso caimos para midia em vez de ficar mudo.
        val wantCall = when (prefs.audioMode) {
            AudioMode.CALL -> true
            AudioMode.MEDIA -> false
            AudioMode.AUTO -> route.hasCallProfile()
        }
        val inCall = if (wantCall) route.engage() else false

        val player = PcmPlayer(
            sampleRate = 22050,
            usage = if (inCall) {
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            } else {
                AudioAttributes.USAGE_MEDIA
            }
        )

        // O microfone do fone so existe no perfil de chamada. Fora dele, quem
        // escuta e o celular.
        voice?.useHeadsetMic(inCall)

        livePlayer = player
        interrupted = false

        CodecBus.update {
            it.copy(
                audio = if (inCall) "chamada (sco)" else "midia (a2dp)",
                transcript = "",
                response = "",
                error = ""
            )
        }
        if (seed.isNotBlank()) CodecBus.transcript(seed)

        try {
            // Respiro para o canal SCO subir; sem isso o inicio do toque
            // desaparece no fone bluetooth. Em midia nao ha o que esperar.
            if (inCall) delay(250)

            // O toque de chamada custa mais de um segundo por pergunta.
            // No modo minimo ele sai do caminho e a escuta abre direto.
            if (!prefs.sfxMinimal) {
                CodecBus.phase(Phase.RINGING)
                player.playAndWait(sfx(Sfx.CALL) { CodecSfx.ring(2) })
                delay(80)
            }

            var turn = 0
            while (true) {
                CodecBus.phase(Phase.LISTENING)
                player.playAndWait(sfx(Sfx.OPEN) { CodecSfx.connect() })
                delay(80)

                val question = listenForQuestion(voice)
                if (question.isNullOrBlank()) {
                    if (turn == 0) {
                        CodecBus.transcript("(nada captado)")
                        player.playAndWait(sfx(Sfx.FAIL) { CodecSfx.staticBurst(0.2) })
                    }
                    break
                }

                CodecBus.transcript(question)

                // "Desliga" nao vai para o modelo: encerra a chamada aqui.
                if (VoiceCommands.isHangUp(question, VoiceCommands.parse(prefs.hangUpPhrases))) {
                    Log.i(TAG, "comando de desligar")
                    break
                }

                // Musica tambem nao vai para o modelo. Resolver aqui evita a
                // ida e volta pela rede so para acabar tocando algo, e mantem
                // o comando funcionando mesmo sem chave de API configurada.
                if (prefs.musicEnabled) {
                    val acao = MusicCommands.parse(
                        question,
                        MusicCommands.parseShortcuts(prefs.musicShortcuts)
                    )
                    if (acao != null) {
                        handleMusic(acao, player)
                        turn++
                        if (!prefs.followUp || turn >= 8) break
                        CodecBus.update { it.copy(transcript = "", response = "") }
                        continue
                    }
                }

                // Fim da escuta: confirma que a pergunta fechou e saiu.
                // Escrito sem esperar, para o som sair enquanto a requisicao
                // ja esta a caminho do modelo.
                player.write(CodecSfx.ack(prefs.sfxVolume))

                if (!answer(question, player)) break

                turn++
                // Se voce cortou a resposta, a linha continua aberta mesmo com
                // o seguimento desligado: cortar significa que quer falar.
                if (!interrupted && (!prefs.followUp || turn >= 8)) break
                interrupted = false
                CodecBus.update { it.copy(transcript = "", response = "") }
            }
        } catch (e: CancellationException) {
            // Cancelamento NAO e falha: e alguem pedindo para parar — calibracao
            // comecando, servico sendo desligado, sessao substituida. Capturar
            // junto com Exception fazia duas coisas erradas ao mesmo tempo:
            // mostrava "StandaloneCoroutine was cancelled" como se fosse erro
            // do app, e engolia o cancelamento, quebrando o encadeamento que o
            // Kotlin usa para desmontar corrotinas em ordem.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "erro na sessao", e)
            CodecBus.error(e.message ?: "erro desconhecido")
        } finally {
            pendingQuestion = null

            // A ESCUTA VOLTA PRIMEIRO. Antes isto ficava depois do bipe de
            // encerramento e do dreno do player, e qualquer atraso ali —
            // inclusive um dreno que podia segurar minutos — deixava o app
            // surdo nesse meio tempo. Nada relacionado a saida de audio deve
            // ficar entre o fim da sessao e a volta da escuta.
            voice?.useHeadsetMic(false)
            voice?.resumeCapture()
            voice?.listenForWake()
            CodecBus.phase(Phase.STANDBY)
            CodecBus.update { it.copy(heard = "") }

            // Agora sim, a limpeza da saida. Se algo aqui demorar, o pior que
            // acontece e o bipe sair torto: a escuta ja esta de pe.
            try {
                player.playAndWait(sfx(Sfx.OVER) { CodecSfx.disconnect() })
            } catch (_: Exception) {}
            runCatching { player.finish() }
            livePlayer = null
            runCatching { systemTts?.stop() }
            runCatching { route.release() }
        }
    }

    /**
     * Abre um turno de escuta e espera o motor de voz resolver.
     * Volta null quando ninguem falou.
     */
    private suspend fun listenForQuestion(voice: VoiceEngine?): String? =
        if (prefs.sttEngine == SttEngine.ANDROID) {
            listenWithAndroid(voice)
        } else {
            listenWithVosk(voice)
        }

    /** Caminho offline: o proprio VoiceEngine transcreve. */
    private suspend fun listenWithVosk(voice: VoiceEngine?): String? {
        if (voice == null) return null
        val deferred = CompletableDeferred<String?>()
        pendingQuestion = deferred
        // Um comando so. O listenForQuestion ja entra desmutado, com o relogio
        // de silencio zerado e o reconhecedor limpo, tudo na mesma transicao.
        voice.listenForQuestion()
        val result = withTimeoutOrNull(30_000) { deferred.await() }
        voice.idle()
        pendingQuestion = null
        return result
    }

    /**
     * Caminho do motor do Google. Ele exige o microfone so para si, entao o
     * VoiceEngine solta a captura antes e retoma depois — e por isso a palavra
     * de ativacao fica desligada durante este trecho, o que nao importa: voce
     * ja esta dentro da chamada.
     */
    private suspend fun listenWithAndroid(voice: VoiceEngine?): String? {
        val stt = androidStt ?: AndroidStt(this).also { androidStt = it }
        if (!stt.isAvailable()) {
            CodecBus.error("reconhecimento do Android indisponivel; usando o Vosk")
            return listenWithVosk(voice)
        }

        voice?.pauseCapture()
        // O AudioRecord nao fecha instantaneamente; sem este respiro o
        // SpeechRecognizer as vezes recebe ERROR_AUDIO logo de cara.
        delay(180)

        return try {
            val r = stt.listen(
                preferOffline = prefs.sttPreferOffline,
                endSilenceMs = prefs.endSilenceMs
            ) { partial -> CodecBus.transcript(partial) }

            when (r) {
                is AndroidStt.Result.Ok -> r.text
                is AndroidStt.Result.Silence -> null
                is AndroidStt.Result.Error -> {
                    CodecBus.error(r.message)
                    null
                }
            }
        } finally {
            // NonCancellable e essencial: chamada suspensa dentro de finally
            // lanca na hora se a corrotina ja foi cancelada, e ai o
            // resumeCapture() abaixo nunca rodava. Era exatamente esse o bug
            // de "nao ativa mais depois de desligar" — o microfone ficava
            // solto para sempre.
            withContext(NonCancellable) {
                // O destroy() do SpeechRecognizer roda no looper principal e
                // leva um instante para soltar o dispositivo.
                delay(250)
                // Só devolve a captura. Quem decide o proximo modo e quem
                // chamou: mandar idle() aqui criava uma janela em que o motor
                // ficava mudo esperando alguem lembrar de religa-lo.
                voice?.resumeCapture()
            }
        }
    }

    /** @return false se deu erro e a chamada deve cair. */
    private suspend fun answer(question: String, player: PcmPlayer): Boolean {
        val llm = buildClient()
        if (llm == null) {
            CodecBus.error("falta a chave do provedor ${prefs.provider.label}")
            runCatching { player.playAndWait(sfx(Sfx.FATAL) { CodecSfx.disconnect() }) }
            return false
        }

        CodecBus.phase(Phase.THINKING)
        conversation.user(question)
        val eleven = if (prefs.ttsEngine == TtsEngine.ELEVEN &&
            prefs.elevenKey.isNotBlank() && prefs.voiceId.isNotBlank()
        ) {
            ElevenLabsTts(prefs.elevenKey, prefs.voiceId, prefs.ttsModel, prefs.radioMix)
        } else null

        val queue = Channel<String>(Channel.UNLIMITED)
        var spokeAnything = false

        /** Prepara o canal de audio na primeira frase que sai. */
        fun onFirstSound() {
            if (spokeAnything) return
            spokeAnything = true
            CodecBus.phase(Phase.SPEAKING)
            runCatching { player.write(CodecSfx.staticBurst(0.12)) }
            // Abre o microfone durante a fala. So a palavra de ativacao e
            // escutada aqui, e o cancelamento de eco segura o resto.
            if (prefs.bargeIn) {
                engine?.listenForWake()
                engine?.muted = false
            }
        }

        val speaker: Job = if (eleven != null) {
            // O ElevenLabs ja entrega o audio em streaming dentro da frase,
            // entao um estagio so basta: os bytes vao para o player conforme
            // chegam da rede.
            scope.launch {
                for (chunk in queue) {
                    if (interrupted) break
                    onFirstSound()
                    try {
                        eleven.speak(chunk, player)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "ElevenLabs falhou, caindo para a voz local", e)
                        if (!speakLocal(chunk, player)) systemTts().speakPlain(chunk)
                    }
                }
            }
        } else {
            // Voz local em dois estagios. A sintese produz PCM num canal e a
            // reproducao consome. Assim a frase seguinte e sintetizada enquanto
            // a atual ainda esta tocando, em vez de esperar o alto-falante
            // esvaziar para so entao chamar o motor de novo.
            val pcmQueue = Channel<ShortArray>(capacity = 3)

            val synth = scope.launch {
                try {
                    for (chunk in queue) {
                        if (interrupted) break
                        // O sherpa vem primeiro quando escolhido, e cai para
                        // o TTS do aparelho se o modelo nao estiver instalado.
                        // A queda e silenciosa de proposito: perder a voz
                        // bonita e aceitavel, perder a resposta nao.
                        val pcm = runCatching {
                            sherpaRender(chunk, player.sampleRate)
                                ?: systemTts().renderFiltered(
                                    text = chunk,
                                    sampleRate = player.sampleRate,
                                    radioMix = prefs.radioMix,
                                    voiceId = prefs.androidVoice,
                                    rate = prefs.speechRate,
                                    pitch = prefs.speechPitch
                                )
                        }.getOrNull()

                        if (pcm == null) {
                            // Sintese falhou nesta frase: fala sem filtro, que
                            // e melhor do que engolir um pedaco da resposta.
                            onFirstSound()
                            systemTts().speakPlain(chunk)
                        } else if (pcm.isNotEmpty()) {
                            pcmQueue.send(pcm)
                        }
                    }
                } finally {
                    pcmQueue.close()
                }
            }

            scope.launch {
                for (pcm in pcmQueue) {
                    if (interrupted) break
                    onFirstSound()
                    player.write(pcm)
                }
                synth.join()
            }
        }

        val chunker = SentenceChunker()
        var failure: String? = null

        try {
            // O prompt de sistema e a memoria viajam juntos, montados a cada
            // chamada: assim um fato lembrado agora ja vale na pergunta
            // seguinte, sem reiniciar o servico.
            val prompt = prefs.systemPrompt + memoria.paraPrompt()

            val busca: ((String) -> String)? = montarBusca()

            val full = llm.stream(prompt, conversation.snapshot(), busca) { delta ->
                CodecBus.appendResponse(delta)
                chunker.feed(delta) { queue.trySend(it) }
            }
            chunker.flush { queue.trySend(it) }
            conversation.assistant(full)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: "falha ao falar com ${prefs.provider.label}"
            Log.e(TAG, "erro no provedor ${prefs.provider.name}", e)
        } finally {
            withContext(NonCancellable) {
                queue.close()
                speaker.join()
                if (prefs.bargeIn) engine?.muted = true
                if (!interrupted) player.waitUntilDrained()
            }
        }

        if (failure != null) {
            CodecBus.error(failure)
            runCatching { player.playAndWait(sfx(Sfx.FATAL) { CodecSfx.disconnect() }) }
            runCatching { systemTts().speakPlain("Falha na transmissao.") }
            return false
        }

        // A memoria e atualizada DEPOIS de responder, em segundo plano. E uma
        // segunda chamada ao provedor: fazer antes acrescentaria a latencia
        // dela ao silencio que voce ja espera entre a pergunta e a resposta.
        extrairMemoria()
        return true
    }

    /**
     * Monta a funcao de busca entregue ao provedor, ou null.
     *
     * Devolver null quando nao ha chave nao e detalhe: e o que faz a ferramenta
     * sequer ser declarada na chamada. Se ela fosse oferecida sem chave, o
     * modelo tentaria usar, receberia erro e gastaria uma frase da resposta
     * explicando que nao conseguiu pesquisar — pior do que nunca ter tentado.
     */
    private fun montarBusca(): ((String) -> String)? {
        if (!prefs.webSearch || prefs.braveKey.isBlank()) return null
        val cliente = WebSearch(prefs.braveKey)
        return { consulta ->
            // O HUD mostra o que ele foi procurar. Sem isso, a busca aparece
            // como alguns segundos de silencio sem explicacao nenhuma.
            val antes = CodecBus.state.value.engine
            CodecBus.update { it.copy(engine = "buscando: $consulta") }
            val resultado = runCatching { cliente.buscar(consulta).paraModelo() }
                .getOrElse { "A busca falhou: ${it.message}" }
            CodecBus.update { it.copy(engine = antes) }
            resultado
        }
    }

    /**
     * Pergunta ao modelo o que vale a pena lembrar desta conversa.
     *
     * Roda solta, sem ninguem esperando, e engole qualquer erro: memoria e um
     * extra. Se a cota acabou ou a rede caiu, a conversa ja aconteceu e foi
     * respondida — falhar aqui nao pode aparecer como erro na tela.
     */
    private fun extrairMemoria() {
        if (!prefs.memoriaAtiva) return
        val historico = conversation.snapshot()
        if (historico.size < 2) return

        scope.launch {
            runCatching {
                val cliente = buildClient() ?: return@launch
                // Sem ferramenta de busca aqui: a tarefa e ler a conversa, nao
                // pesquisar. Oferecer a busca so daria ao modelo a chance de
                // gastar cota atras de algo irrelevante.
                val resposta = cliente.stream(
                    Memoria.PROMPT_EXTRACAO + memoria.paraPrompt(),
                    historico,
                    null
                ) { }
                val novos = Memoria.lerExtracao(resposta)
                val gravados = novos.count { memoria.lembrar(it) }
                if (gravados > 0) {
                    Log.i(TAG, "memoria: $gravados fato(s) novo(s)")
                    CodecBus.update { it.copy(engine = "memoria: ${memoria.listar().size} fatos") }
                }
            }.onFailure { Log.w(TAG, "extracao de memoria falhou", it) }
        }
    }

    /**
     * Executa o comando de musica e confirma por voz.
     *
     * A confirmacao importa mais do que parece: o intent do Spotify e uma via
     * de mao unica, sem retorno nenhum. Nao da para saber o que comecou a
     * tocar, entao a unica forma de voce perceber que o pedido foi entendido e
     * o app repetir o que mandou.
     */
    private fun handleMusic(action: MusicCommands.Action, player: PcmPlayer) {
        if (!music.isSpotifyInstalled()) {
            CodecBus.error("Spotify nao esta instalado")
            runCatching { player.playAndWait(sfx(Sfx.FAIL) { CodecSfx.staticBurst(0.2) }) }
            return
        }

        val (ok, fala) = when (action) {
            is MusicCommands.Action.Play -> {
                val alvo = action.query
                val enviado = if (alvo.startsWith("spotify:") || alvo.startsWith("http")) {
                    music.playUri(alvo)
                } else {
                    music.playSearch(alvo, action.focus)
                }
                enviado to "Tocando ${action.alias ?: action.query}."
            }
            MusicCommands.Action.Pause -> music.pause() to "Pausado."
            MusicCommands.Action.Resume -> music.resume() to "Retomando."
            MusicCommands.Action.Next -> music.next() to "Proxima."
            MusicCommands.Action.Previous -> music.previous() to "Anterior."
        }

        CodecBus.update { it.copy(response = fala) }
        if (!ok) {
            CodecBus.error("nao consegui acionar o Spotify")
            runCatching { player.playAndWait(sfx(Sfx.FAIL) { CodecSfx.staticBurst(0.2) }) }
            return
        }

        player.write(CodecSfx.ack(prefs.sfxVolume))
        CodecBus.phase(Phase.SPEAKING)
        runCatching {
            if (!speakLocal(fala, player)) systemTts().speakPlain(fala)
        }
        player.waitUntilDrained()
    }

    /**
     * Sintetiza pelo sherpa, ou null quando ele nao e o motor escolhido ou o
     * modelo nao carregou. Null aqui significa "use o proximo motor".
     */
    private fun sherpaRender(texto: String, sampleRate: Int): ShortArray? {
        if (prefs.ttsEngine != TtsEngine.SHERPA) return null
        val t = sherpaTts ?: SherpaTts(this).also { sherpaTts = it }
        if (!t.isReady && !t.load()) {
            CodecBus.update { it.copy(engine = "voz sherpa: ${t.status}") }
            return null
        }
        return t.renderFiltered(
            text = texto,
            sampleRate = sampleRate,
            radioMix = prefs.radioMix,
            speaker = prefs.sherpaVoiceId,
            speed = prefs.speechRate
        )
    }

    private fun systemTts(): SystemTts {
        systemTts?.let { return it }
        // TextToSpeech quer nascer numa thread com Looper; as corrotinas de IO
        // nao tem. Criar no looper principal e esperar evita o caso em que a
        // voz local simplesmente nunca inicializa.
        val created = java.util.concurrent.CompletableFuture<SystemTts>()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            created.complete(SystemTts(this))
        }
        val t = created.get()
        systemTts = t
        return t
    }

    /** Voz do Android sintetizada em arquivo e passada pelo filtro de radio. */
    private fun speakLocal(text: String, player: PcmPlayer): Boolean =
        systemTts().speakFiltered(
            text = text,
            player = player,
            radioMix = prefs.radioMix,
            voiceId = prefs.androidVoice,
            rate = prefs.speechRate,
            pitch = prefs.speechPitch
        )

    // ------------------------------------------------------------ infraestrutura

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Personal Codec", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Mantem a escuta pela palavra de ativacao" }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, CodecService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PERSONAL CODEC 140.85")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Desligar", stopIntent)
            .build()
    }

    private fun shutdown() {
        UiSfx.play(Sfx.UI_EXIT)
        watchdog?.cancel()
        watchdog = null
        sessionJob?.cancel()
        bootJob?.cancel()
        engine?.stop()
        engine = null
        systemTts?.release()
        systemTts = null
        route.release()
        CodecBus.update { it.copy(phase = Phase.OFF, engine = "desligado") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Cada passo isolado. Antes, uma excecao em qualquer um deles abortava
        // o resto da limpeza e ainda derrubava o processo — o AudioRecord ficava
        // aberto, e a proxima abertura do app achava o microfone ocupado.
        //
        // O 'criado' cobre o caso mais traicoeiro: se o onCreate morreu antes
        // de inicializar 'route', tocar nele aqui lanca
        // UninitializedPropertyAccessException e MASCARA o erro de verdade,
        // que aconteceu la atras.
        runCatching { watchdog?.cancel() }
        runCatching { sessionJob?.cancel() }
        runCatching { bootJob?.cancel() }
        runCatching { engine?.stop() }
        engine = null
        // O sherpa segura memoria nativa: sem o release ela nao volta quando o
        // servico morre, e o proximo start abriria um segundo modelo por cima.
        runCatching { sherpaTts?.release() }
        sherpaTts = null
        runCatching { systemTts?.release() }
        systemTts = null
        if (criado) runCatching { route.release() }
        runCatching { scope.cancel() }
        runCatching {
            CodecBus.update { it.copy(phase = Phase.OFF, engine = "desligado") }
        }
        super.onDestroy()
    }
}
