package br.unesp.pedec.stt

/** Quem transcreve a PERGUNTA. A palavra de ativacao e sempre do Vosk. */
enum class SttEngine(val label: String, val hint: String) {
    ANDROID(
        "Android",
        "motor do Google: soletra, entende ingles, muito mais preciso"
    ),
    VOSK(
        "Vosk",
        "offline sempre, mas lexico fixo em portugues: nao soletra nem fala ingles"
    );

    companion object {
        fun from(name: String?): SttEngine =
            entries.firstOrNull { it.name == name } ?: ANDROID
    }
}
