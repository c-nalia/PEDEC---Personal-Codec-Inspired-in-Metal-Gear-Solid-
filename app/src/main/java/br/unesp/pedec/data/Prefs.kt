package br.unesp.pedec.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import br.unesp.pedec.audio.AudioMode
import br.unesp.pedec.llm.Provider
import br.unesp.pedec.stt.SttEngine
import br.unesp.pedec.tts.TtsEngine

/**
 * Chaves de API e preferencias, guardadas em EncryptedSharedPreferences
 * (AES-256-GCM com chave no Keystore do aparelho).
 */
class Prefs private constructor(private val sp: SharedPreferences) {

    companion object {
        @Volatile private var instance: Prefs? = null

        fun get(ctx: Context): Prefs = instance ?: synchronized(this) {
            instance ?: build(ctx.applicationContext).also { instance = it }
        }

        /** Nome do arquivo cifrado. Fora do metodo porque o apagamos no reparo. */
        private const val ARQUIVO = "pedec_secure"

        /**
         * Monta as preferencias cifradas, com dois reparos.
         *
         * ISTO ERA UMA FONTE DE CRASH NA ABERTURA, e das piores: fatal, logo no
         * onCreate, e repetida a cada tentativa de abrir o app.
         *
         * A chave mestra vive no Keystore do aparelho. Ela pode ser invalidada
         * sem o app fazer nada de errado: restauracao de backup, troca de
         * bloqueio de tela, atualizacao de seguranca, ou simplesmente Keystore
         * corrompido — comum em ROM de fabricante. Quando isso acontece,
         * EncryptedSharedPreferences.create() lanca, e como Prefs.get() e
         * chamado no onCreate da Activity, do Service e no BootReceiver, o app
         * passa a morrer sempre, sem nunca mostrar tela.
         *
         * Reparo 1: apagar o arquivo ilegivel e recriar. Perde-se o conteudo —
         * mas ele ja estava ilegivel de qualquer forma, entao nao ha o que
         * salvar. Voce reconfigura as chaves de API uma vez.
         *
         * Reparo 2: se ate isso falhar (Keystore realmente quebrado), cai para
         * SharedPreferences comum. Menos seguro, e por isso e o ultimo recurso
         * e nao o padrao — mas um app que abre sem cifrar as chaves e melhor do
         * que um app que nao abre.
         */
        private fun build(ctx: Context): Prefs {
            runCatching { return Prefs(criarCifrado(ctx)) }
                .onFailure { Log.w(TAG, "preferencias cifradas ilegiveis; recriando", it) }

            // Reparo 1: o arquivo e a chave ficaram fora de sincronia.
            runCatching {
                ctx.deleteSharedPreferences(ARQUIVO)
                return Prefs(criarCifrado(ctx))
            }.onFailure { Log.e(TAG, "nao foi possivel recriar o arquivo cifrado", it) }

            // Reparo 2: sem cifra, mas o app abre.
            Log.e(TAG, "caindo para preferencias sem cifra")
            return Prefs(ctx.getSharedPreferences("pedec_plain", Context.MODE_PRIVATE))
        }

        private fun criarCifrado(ctx: Context): SharedPreferences {
            val key = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                ctx,
                ARQUIVO,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private const val TAG = "PedecPrefs"

        /**
         * O prompt e escrito para SER OUVIDO, nao lido, e essa e a restricao
         * que dita quase tudo aqui.
         *
         * Cada secao existe por um motivo concreto:
         *
         * - "Voce e ouvido" vem primeiro porque e a restricao mais forte. Sem
         *   ela o modelo devolve markdown, listas numeradas e blocos de codigo,
         *   e o sintetizador le "asterisco asterisco" em voz alta.
         *
         * - Os limites de tamanho sao em FRASES, nao em palavras ou tokens.
         *   Modelo nao conta token de forma confiavel, mas conta frase bem.
         *
         * - Ha um exemplo de resposta ruim e um de boa. Isso vale mais que
         *   qualquer quantidade de instrucao abstrata: o modelo generaliza de
         *   exemplo com muito mais precisao do que de regra.
         *
         * - A secao de busca diz quando NAO buscar. Sem esse contrapeso o
         *   modelo pesquisa "qual a complexidade do quicksort" e voce espera
         *   tres segundos por algo que ele ja sabia.
         *
         * - A secao de incerteza e curta de proposito. Assistente falado que
         *   enche de ressalva fica insuportavel; uma frase resolve.
         */
        const val DEFAULT_SYSTEM_PROMPT = """Voce e o PERSONAL CODEC, assistente pessoal por radio de um estudante de Ciencia da Computacao com base solida em software e hardware.

VOCE E OUVIDO, NUNCA LIDO.
A resposta sai por um fone de conducao ossea, sintetizada em voz. Escreva em portugues do Brasil, em prosa corrida. Nada de markdown, listas, numeracao, blocos de codigo, emoji, parenteses explicativos ou simbolo que nao se pronuncie. Se precisar enumerar, use "primeiro", "depois", "por fim" dentro da frase. Numero, unidade e sigla sempre por extenso quando a leitura for ambigua: diga "dezesseis kilohertz", nao "16 kHz".

TAMANHO.
Pergunta objetiva: ate 3 frases. Pergunta que exige raciocinio: ate 6. Nunca mais que isso sem ele pedir. Se o assunto nao couber, responda o essencial e ofereca continuar.

TOM.
Seco e operacional, como comunicacao tatica por radio. Sem saudacao, sem "claro!", sem se apresentar de novo, sem repetir a pergunta antes de responder, sem resumir no fim o que acabou de dizer. Ele e tecnico: nao explique o basico e nao adocice.

RUIM: "Otima pergunta! Vamos analisar a complexidade do quicksort. O quicksort e um algoritmo de ordenacao que..."
BOM: "Caso medio n log n. Pior caso n ao quadrado, quando o pivo cai sempre no extremo."

BUSCA NA WEB.
Voce tem a ferramenta buscar_na_web. Use quando a resposta depender de algo atual ou verificavel: noticia, cotacao, placar, versao de software, documentacao que muda, evento com data. Nao use para conhecimento estavel, definicao, matematica, algoritmo, nem para conversa. Uma busca custa segundos de silencio para quem esta ouvindo, entao so pesquise quando responder de cabeca seria errado. Ao usar, incorpore o resultado na resposta com naturalidade e cite a fonte so quando ela importar para a confianca.

O QUE VOCE NAO SABE.
Se nao souber e a busca nao resolver, diga em uma frase e pare. Nao invente numero, versao, data nem citacao. Uma ressalva basta: nao empilhe avisos.

AUDIO.
O reconhecimento de voz erra. Se a transcricao vier truncada ou sem sentido, peca para repetir em vez de adivinhar. Se precisar ditar comando ou trecho de codigo, va devagar e soletre o que for ambiguo, dizendo a pontuacao por extenso."""
    }

    // ------------------------------------------------------------- provedor
    //
    // Cada provedor guarda a propria chave, modelo e URL. Assim voce troca de
    // servico no celular sem perder o que ja tinha configurado no anterior:
    // util quando a cota gratuita de um acaba no meio do dia.

    var provider: Provider
        get() = Provider.from(sp.getString("provider", null))
        set(v) = sp.edit().putString("provider", v.name).apply()

    fun key(p: Provider): String = sp.getString("key_${p.name}", "") ?: ""
    fun setKey(p: Provider, v: String) {
        sp.edit().putString("key_${p.name}", v.trim()).apply()
    }

    fun model(p: Provider): String =
        sp.getString("model_${p.name}", null)?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun setModel(p: Provider, v: String) {
        sp.edit().putString("model_${p.name}", v.trim()).apply()
    }

    fun baseUrl(p: Provider): String =
        sp.getString("url_${p.name}", null)?.takeIf { it.isNotBlank() } ?: p.baseUrl
    fun setBaseUrl(p: Provider, v: String) {
        sp.edit().putString("url_${p.name}", v.trim()).apply()
    }

    var elevenKey: String
        get() = sp.getString("eleven_key", "") ?: ""
        set(v) = sp.edit().putString("eleven_key", v).apply()

    /**
     * Grafias aceitas para a palavra de ativacao, separadas por virgula.
     * "Pedec" nao existe no lexico do modelo, entao o reconhecedor devolve
     * aproximacoes; a lista e o que compensa isso.
     */
    var wakePhrases: String
        get() = sp.getString("wake_phrases", "") ?: ""
        set(v) = sp.edit().putString("wake_phrases", v).apply()

    /** ID da voz escolhida no ElevenLabs. */
    var voiceId: String
        get() = sp.getString("voice_id", "") ?: ""
        set(v) = sp.edit().putString("voice_id", v).apply()

    var voiceName: String
        get() = sp.getString("voice_name", "") ?: ""
        set(v) = sp.edit().putString("voice_name", v).apply()

    var ttsModel: String
        get() = sp.getString("tts_model", "eleven_multilingual_v2") ?: "eleven_multilingual_v2"
        set(v) = sp.edit().putString("tts_model", v).apply()

    /**
     * Nivel de esforco, exclusivo da Anthropic. Os provedores compativeis com
     * a OpenAI nao tem parametro equivalente e simplesmente ignoram isto.
     */
    var effort: String
        get() = sp.getString("effort", "medium") ?: "medium"
        set(v) = sp.edit().putString("effort", v).apply()

    var systemPrompt: String
        get() = sp.getString("system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(v) = sp.edit().putString("system_prompt", v).apply()

    // ------------------------------------------------------- busca e memoria

    /** Chave da Brave Search. api.search.brave.com — gratuita ate 2000/mes. */
    var braveKey: String
        get() = sp.getString("brave_key", "") ?: ""
        set(v) = sp.edit().putString("brave_key", v.trim()).apply()

    /**
     * Oferece a busca ao modelo como ferramenta.
     *
     * Nasce ligado, mas so vale com a chave preenchida: sem ela o servico nem
     * declara a ferramenta, entao o modelo nao tenta usar e nao ha resposta
     * dizendo "nao consegui pesquisar".
     */
    var webSearch: Boolean
        get() = sp.getBoolean("web_search", true)
        set(v) = sp.edit().putBoolean("web_search", v).apply()

    /** Extrai fatos duraveis ao fim de cada chamada e guarda localmente. */
    var memoriaAtiva: Boolean
        get() = sp.getBoolean("memoria_ativa", true)
        set(v) = sp.edit().putBoolean("memoria_ativa", v).apply()

    /** Numero do locutor na voz de saida, para modelos com mais de uma voz. */
    var sherpaVoiceId: Int
        get() = sp.getInt("sherpa_voice_id", 0)
        set(v) = sp.edit().putInt("sherpa_voice_id", v).apply()

    // -------------------------------------------------------- microfone

    /**
     * 6 = VOICE_RECOGNITION, 1 = MIC, 7 = VOICE_COMMUNICATION, 0 = DEFAULT.
     * MIC e o cru: sem processamento nenhum do fabricante. Quando o
     * reconhecimento falha em tudo, e o primeiro a testar.
     */
    var micSource: Int
        get() = sp.getInt("mic_source", 6)
        set(v) = sp.edit().putInt("mic_source", v).apply()

    /** Cancelador de eco. Desligado por padrao: ja apagou fala em teste. */
    var useAec: Boolean
        get() = sp.getBoolean("use_aec", false)
        set(v) = sp.edit().putBoolean("use_aec", v).apply()

    /** Supressor de ruido. Desligado por padrao, mesma razao. */
    var useNs: Boolean
        get() = sp.getBoolean("use_ns", false)
        set(v) = sp.edit().putBoolean("use_ns", v).apply()

    // ------------------------------------------------------- reconhecimento

    /**
     * Quem transcreve a PERGUNTA. A palavra de ativacao continua sempre no
     * Vosk: vigiar uma palavra conhecida e barato, offline e instantaneo.
     */
    var sttEngine: SttEngine
        get() = SttEngine.from(sp.getString("stt_engine", null))
        set(v) = sp.edit().putString("stt_engine", v.name).apply()

    /**
     * Pede ao motor do Android para usar o pacote de idioma baixado no
     * aparelho. Sem rede e mais rapido, mas exige o pacote pt-BR instalado
     * em Configuracoes > Sistema > Idiomas > Reconhecimento de voz.
     */
    var sttPreferOffline: Boolean
        get() = sp.getBoolean("stt_prefer_offline", false)
        set(v) = sp.edit().putBoolean("stt_prefer_offline", v).apply()

    // ----------------------------------------------------------------- voz

    var ttsEngine: TtsEngine
        get() = TtsEngine.from(sp.getString("tts_engine", null))
        set(v) = sp.edit().putString("tts_engine", v.name).apply()

    /** Nome da voz do motor do Android. Vazio = a que o sistema escolher. */
    var androidVoice: String
        get() = sp.getString("android_voice", "") ?: ""
        set(v) = sp.edit().putString("android_voice", v).apply()

    /** Velocidade da fala local. 1.0 = normal. */
    var speechRate: Float
        get() = sp.getFloat("speech_rate", 1.05f)
        set(v) = sp.edit().putFloat("speech_rate", v).apply()

    /** Tom da fala local. Abaixo de 1.0 fica mais grave, combina com o codec. */
    var speechPitch: Float
        get() = sp.getFloat("speech_pitch", 0.9f)
        set(v) = sp.edit().putFloat("speech_pitch", v).apply()

    /**
     * Falar a palavra de ativacao durante a resposta corta a fala na hora.
     * Desligue se o aparelho estiver no alto-falante e ele se auto-interromper:
     * sem fone, o microfone escuta a propria voz do assistente.
     */
    var bargeIn: Boolean
        get() = sp.getBoolean("barge_in", true)
        set(v) = sp.edit().putBoolean("barge_in", v).apply()

    /** Frases que encerram a chamada, separadas por virgula. */
    var hangUpPhrases: String
        get() = sp.getString("hangup_phrases", null)
            ?: br.unesp.pedec.voice.VoiceCommands.DEFAULT_HANGUP.joinToString(", ")
        set(v) = sp.edit().putString("hangup_phrases", v).apply()

    // ----------------------------------------------------------------- musica

    /** Aceita "toca ..." e transporte de midia antes de mandar para o modelo. */
    var musicEnabled: Boolean
        get() = sp.getBoolean("music_enabled", true)
        set(v) = sp.edit().putBoolean("music_enabled", v).apply()

    /**
     * Atalhos, um por linha, no formato `apelido = endereco`.
     * O endereco pode ser spotify:playlist:ID ou o link do botao compartilhar.
     */
    var musicShortcuts: String
        get() = sp.getString("music_shortcuts", "") ?: ""
        set(v) = sp.edit().putString("music_shortcuts", v).apply()

    // -------------------------------------------------------------- latencia

    /**
     * Silencio, em ms, que encerra a sua pergunta. Menor responde antes, mas
     * corta quem pensa no meio da frase.
     */
    var endSilenceMs: Int
        get() = sp.getInt("end_silence_ms", 900)
        set(v) = sp.edit().putInt("end_silence_ms", v).apply()

    /**
     * Pula o toque de chamada e vai direto para a escuta. Economiza mais de um
     * segundo por pergunta, ao custo da abertura dramatica.
     */
    var sfxMinimal: Boolean
        get() = sp.getBoolean("sfx_minimal", false)
        set(v) = sp.edit().putBoolean("sfx_minimal", v).apply()

    /**
     * Perfil de audio usado durante a chamada. Existe porque nem todo fone de
     * conducao ossea se comporta bem no perfil de chamada do bluetooth.
     */
    var audioMode: AudioMode
        get() = AudioMode.from(sp.getString("audio_mode", null))
        set(v) = sp.edit().putString("audio_mode", v.name).apply()

    /** Intensidade do filtro de radio, 0f = voz limpa, 1f = codec pesado. */
    var radioMix: Float
        get() = sp.getFloat("radio_mix", 0.75f)
        set(v) = sp.edit().putFloat("radio_mix", v).apply()

    /**
     * true toca os efeitos inteiros; false corta os longos para a conversa
     * comecar antes. Cada pergunta paga esse tempo, entao o padrao e curto.
     */
    var sfxFull: Boolean
        get() = sp.getBoolean("sfx_full", false)
        set(v) = sp.edit().putBoolean("sfx_full", v).apply()

    var sfxVolume: Float
        get() = sp.getFloat("sfx_volume", 0.85f)
        set(v) = sp.edit().putFloat("sfx_volume", v).apply()

    /** Mantem a conversa aberta apos a resposta, para perguntas de seguimento. */
    var followUp: Boolean
        get() = sp.getBoolean("follow_up", true)
        set(v) = sp.edit().putBoolean("follow_up", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    val isConfigured: Boolean
        get() = provider.let { !it.needsKey || key(it).isNotBlank() }
}
