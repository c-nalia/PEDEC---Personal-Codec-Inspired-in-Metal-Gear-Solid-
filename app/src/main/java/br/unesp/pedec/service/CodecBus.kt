package br.unesp.pedec.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Fase atual da chamada, consumida pelo HUD. */
enum class Phase { OFF, STANDBY, RINGING, LISTENING, THINKING, SPEAKING, ERROR }

data class CodecState(
    val phase: Phase = Phase.OFF,
    val transcript: String = "",
    val response: String = "",
    val error: String = "",
    val headset: Boolean = false,
    /** Estado do motor de voz offline: carregando, pronto ou o motivo da falha. */
    val engine: String = "desligado",
    /** Perfil de audio realmente em uso na ultima chamada. */
    val audio: String = "-",
    /** O que o reconhecedor entende enquanto espera a ativacao. Diagnostico. */
    val heard: String = "",
    /** Amostras coletadas na calibracao da palavra de ativacao. */
    val samples: List<String> = emptyList(),
    /** true enquanto uma amostra esta sendo gravada. */
    val capturing: Boolean = false,
    /** Nivel do microfone em dBFS, para a tela de diagnostico. */
    val micDb: Float = -90f,
    /** true quando o detector de voz considera o quadro atual como fala. */
    val micVoiced: Boolean = false
)

/** Ponte simples entre o servico e a interface. */
object CodecBus {
    private val _state = MutableStateFlow(CodecState())
    val state: StateFlow<CodecState> = _state.asStateFlow()

    /**
     * ISTO PERDIA PEDACOS DA RESPOSTA NA TELA.
     *
     * Antes era `_state.value = block(_state.value)`: le, calcula, escreve. Nao
     * e atomico. Duas threads escrevem aqui o tempo todo — a corrotina do
     * streaming chamando appendResponse a cada token, e o watchdog chamando
     * mic() quatro vezes por segundo. Quando as duas se cruzam, a que escreve
     * por ultimo apaga o trabalho da outra, e como appendResponse acumula
     * (`response + t`), o token perdido some para sempre.
     *
     * O `update` do MutableStateFlow faz compare-and-set em laco: se o valor
     * mudou entre ler e escrever, ele refaz o calculo com o valor novo. Custa
     * praticamente o mesmo e nao perde escrita nenhuma.
     */
    fun update(block: (CodecState) -> CodecState) = _state.update(block)

    fun phase(p: Phase) = update { it.copy(phase = p) }
    fun transcript(t: String) = update { it.copy(transcript = t) }
    fun appendResponse(t: String) = update { it.copy(response = it.response + t) }
    fun resetTurn() = update { it.copy(transcript = "", response = "", error = "") }

    fun clearSamples() = update { it.copy(samples = emptyList(), capturing = false) }
    fun addSample(text: String) = update {
        it.copy(samples = it.samples + text, capturing = false)
    }
    fun dropSample(index: Int) = update {
        it.copy(samples = it.samples.filterIndexed { i, _ -> i != index })
    }
    fun capturing(on: Boolean) = update { it.copy(capturing = on) }
    fun mic(db: Float, voiced: Boolean) = update { it.copy(micDb = db, micVoiced = voiced) }
    fun error(msg: String) = update { it.copy(phase = Phase.ERROR, error = msg) }
}
