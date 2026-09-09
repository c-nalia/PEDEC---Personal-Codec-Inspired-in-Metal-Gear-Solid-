package br.unesp.pedec.tts

import android.content.Context
import android.util.Log
import br.unesp.pedec.audio.RadioFilter
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Voz de saida pelo sherpa-onnx.
 *
 * Vale a pena entender por que este caminho e mais curto que o do TTS do
 * Android. Aquele nao da acesso ao audio: `speak()` toca direto no alto-falante
 * e nao devolve amostra nenhuma. Para conseguir aplicar o filtro de radio foi
 * preciso sintetizar para um arquivo WAV no cache, ler o arquivo de volta,
 * decodificar o cabecalho e so entao filtrar — ida e volta ao disco a cada
 * frase.
 *
 * O sherpa devolve `FloatArray` direto na memoria, normalizado em [-1, 1].
 * O filtro se aplica ali mesmo. Sem arquivo, sem cache para limpar, sem
 * decodificador de WAV.
 *
 * Tem um custo: o modelo sintetiza na taxa DELE, nao na do player, entao
 * sobra uma reamostragem — que e barata e esta em [resample].
 */
class SherpaTts(private val ctx: Context) {

    private var tts: OfflineTts? = null

    var status: String = "nao carregado"
        private set

    val isReady: Boolean get() = tts != null

    /**
     * Carrega o modelo de voz de assets/[MODEL_DIR].
     *
     * Devolve false em vez de lancar excecao: a voz e substituivel, e derrubar
     * o servico porque um modelo opcional faltou seria pior do que continuar
     * com a voz do aparelho.
     */
    @Synchronized
    fun load(): Boolean {
        if (tts != null) return true

        val modelo = acharModelo()
        if (modelo == null) {
            status = "modelo ausente: veja SHERPA-TTS.txt em assets"
            return false
        }

        return try {
            // ---------------------------------------------------------------
            // Os modelos Piper dependem do espeak-ng para converter texto em
            // fonemas, e o espeak abre seus dados com fopen() — funcao do C,
            // que nao enxerga o interior do APK. Passar um caminho de asset
            // aqui resulta em falha sem mensagem util.
            //
            // Por isso a pasta e copiada para filesDir na primeira execucao.
            // Modelos MMS nao usam espeak e pulam esta etapa inteira.
            // ---------------------------------------------------------------
            val dataDir = if (modelo.precisaEspeak) {
                copiarEspeak() ?: run {
                    status = "falhou ao preparar espeak-ng-data"
                    return false
                }
            } else {
                ""
            }

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$MODEL_DIR/${modelo.arquivo}",
                        tokens = "$MODEL_DIR/tokens.txt",
                        lexicon = modelo.lexicon,
                        dataDir = dataDir
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 1
            )

            val t = OfflineTts(assetManager = ctx.assets, config = config)
            tts = t
            status = "pronto (${t.sampleRate()} Hz, ${t.numSpeakers()} voz(es))"
            Log.i(TAG, status)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "falha ao carregar a voz", e)
            status = "falhou: ${e.message}"
            tts = null
            false
        }
    }

    /**
     * Sintetiza, reamostra e aplica o filtro de radio.
     *
     * Assinatura igual a de [SystemTts.renderFiltered] de proposito: o servico
     * troca de motor sem que o resto do fluxo de fala saiba da diferenca.
     *
     * @return null se a sintese falhar, para o chamador cair na voz do aparelho.
     */
    fun renderFiltered(
        text: String,
        sampleRate: Int,
        radioMix: Float,
        speaker: Int = 0,
        speed: Float = 1.0f
    ): ShortArray? {
        if (text.isBlank()) return ShortArray(0)

        // Copia para uma variavel local antes de usar. O compilador recusa
        // smart cast em propriedade mutavel — outra thread poderia zera-la
        // entre a checagem e o uso, e aqui isso e real: release() roda na
        // thread do servico enquanto a sintese roda na thread da fala.
        val t: OfflineTts = tts ?: run {
            if (!load()) return null
            tts ?: return null
        }

        return try {
            val audio = t.generate(text = text, sid = speaker, speed = speed)
            val emTaxa = resample(audio.samples, audio.sampleRate, sampleRate)

            val pcm = ShortArray(emTaxa.size) { i ->
                // O clamp nao e decorativo: o modelo pode passar de 1.0 em
                // picos, e sem limite isso vira estouro de sinal audivel.
                (emTaxa[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            RadioFilter(sampleRate, radioMix).process(pcm)
            pcm
        } catch (e: Throwable) {
            Log.w(TAG, "falha ao sintetizar", e)
            null
        }
    }

    @Synchronized
    fun release() {
        // Zera o campo ANTES de liberar. Se a liberacao lancar, o campo ja
        // esta nulo e a proxima chamada recarrega em vez de usar um ponteiro
        // nativo morto.
        val t = tts
        tts = null
        if (t != null) runCatching { t.release() }
    }

    // ------------------------------------------------------------------ apoio

    private data class Modelo(
        val arquivo: String,
        val precisaEspeak: Boolean,
        val lexicon: String
    )

    /**
     * Descobre qual modelo esta em assets.
     *
     * Deliberadamente nao ha um nome fixo aqui. Os modelos de voz do sherpa tem
     * nomes diferentes conforme a familia e o idioma, e exigir um nome exato
     * so geraria o erro "modelo ausente" para quem baixou um modelo valido de
     * outro idioma. Qualquer .onnx serve; o que muda e se ele precisa do
     * espeak.
     */
    private fun acharModelo(): Modelo? {
        val files = try {
            ctx.assets.list(MODEL_DIR)?.toList().orEmpty()
        } catch (e: Exception) {
            return null
        }
        if ("tokens.txt" !in files) return null
        val onnx = files.firstOrNull { it.endsWith(".onnx") } ?: return null

        // Piper traz espeak-ng-data junto; MMS nao traz. E o unico sinal
        // confiavel, porque o nome do arquivo varia.
        val temEspeak = "espeak-ng-data" in files
        val lexicon = if ("lexicon.txt" in files) "lexicon.txt" else ""
        return Modelo(onnx, temEspeak, lexicon)
    }

    /**
     * Copia assets/[MODEL_DIR]/espeak-ng-data para filesDir, uma vez so.
     *
     * A marca de conclusao e um arquivo gravado no FIM da copia. Sem ela, uma
     * copia interrompida (app fechado no meio) deixaria a pasta pela metade e
     * toda execucao seguinte a consideraria pronta.
     */
    private fun copiarEspeak(): String? {
        val destino = File(ctx.filesDir, "espeak-ng-data")
        val marca = File(ctx.filesDir, ".espeak-ok")
        if (marca.exists() && destino.isDirectory) return ctx.filesDir.absolutePath

        return try {
            destino.deleteRecursively()
            copiarPasta("$MODEL_DIR/espeak-ng-data", destino)
            marca.writeText("ok")
            ctx.filesDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "falha ao copiar espeak-ng-data", e)
            null
        }
    }

    private fun copiarPasta(assetPath: String, destino: File) {
        val filhos = ctx.assets.list(assetPath).orEmpty()
        if (filhos.isEmpty()) {
            destino.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { entrada ->
                destino.outputStream().use { saida -> entrada.copyTo(saida) }
            }
            return
        }
        destino.mkdirs()
        filhos.forEach { copiarPasta("$assetPath/$it", File(destino, it)) }
    }

    companion object {
        private const val TAG = "PedecSherpaTts"
        const val MODEL_DIR = "sherpa-tts"

        /**
         * Reamostragem linear.
         *
         * Interpolacao linear introduz alias em frequencia alta, o que
         * normalmente seria motivo para usar algo melhor. Aqui nao e: logo
         * depois o sinal passa por um passa-baixas de 3 kHz do filtro de radio,
         * que remove justamente a faixa onde o alias apareceria. Gastar CPU com
         * um reamostrador polifasico seria refinar algo que o proximo estagio
         * joga fora.
         */
        fun resample(entrada: FloatArray, de: Int, para: Int): FloatArray {
            if (de == para || entrada.isEmpty()) return entrada
            val razao = de.toDouble() / para
            val n = (entrada.size / razao).toInt()
            if (n <= 0) return FloatArray(0)

            return FloatArray(n) { i ->
                val pos = i * razao
                val esq = pos.toInt()
                val dir = (esq + 1).coerceAtMost(entrada.size - 1)
                val frac = (pos - esq).toFloat()
                entrada[esq] * (1f - frac) + entrada[dir] * frac
            }
        }
    }
}
