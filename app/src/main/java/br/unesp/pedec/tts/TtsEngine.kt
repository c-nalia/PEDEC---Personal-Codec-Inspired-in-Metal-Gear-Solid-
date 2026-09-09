package br.unesp.pedec.tts

/** De onde sai a voz da resposta. */
enum class TtsEngine(val label: String, val hint: String) {
    ANDROID(
        "Android",
        "voz do aparelho, offline e sem cota; passa pelo filtro de radio"
    ),
    SHERPA(
        "Sherpa",
        "voz offline propria, sem cota e sem chave; exige baixar um modelo"
    ),
    ELEVEN(
        "ElevenLabs",
        "voz mais natural, exige chave e consome cota mensal"
    );

    companion object {
        fun from(name: String?): TtsEngine =
            entries.firstOrNull { it.name == name } ?: ANDROID
    }
}
