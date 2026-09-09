package br.unesp.pedec.audio

/**
 * Catalogo de efeitos. Cada entrada aponta para um arquivo em assets/sfx/,
 * todos ja normalizados em PCM 16 bits, mono, 22050 Hz, que e exatamente o
 * formato do PcmPlayer. Nenhuma conversao em tempo de execucao.
 */
enum class Sfx(val asset: String) {
    /** Alerta de deteccao: toca no instante em que a wake word e reconhecida. */
    ALERT("found.wav"),

    /** Chamada entrando. */
    CALL("codeccall.wav"),

    /** Linha aberta, pode falar. */
    OPEN("codecopen.wav"),

    /** Fim da transmissao. */
    OVER("codecover.wav"),

    /** Falha recuperavel: nao entendi, tente de novo. */
    FAIL("doorbuzz.wav"),

    /** Falha grave: API fora do ar, chave invalida. */
    FATAL("gameover.wav"),

    /** Interface: abrir a tela de configuracao. */
    UI_OPEN("itemopen.wav"),

    /** Interface: selecionar uma opcao. */
    UI_SELECT("itemequip.wav"),

    /** Interface: salvar. */
    UI_CONFIRM("itemused.wav"),

    /** Interface: desligar a escuta. */
    UI_EXIT("exit.wav");

    val path: String get() = "sfx/$asset"
}
