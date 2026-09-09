package br.unesp.pedec.audio

/**
 * Como o app conversa com o fone.
 *
 * Nem todo fone de conducao ossea se comporta bem no perfil de chamada. O SCO
 * derruba o audio para banda estreita, alguns aparelhos travam ao entrar e sair
 * dele, e outros simplesmente nao expoem o microfone. Por isso a escolha e sua.
 */
enum class AudioMode(val label: String, val hint: String) {

    /**
     * Perfil de chamada (SCO/HFP). Usa o microfone do fone e a voz sai por ele
     * mesmo com outro som tocando. E o unico modo realmente hands-free, mas o
     * audio fica em banda estreita e alguns fones nao gostam.
     */
    CALL(
        "Chamada",
        "usa o microfone do fone; audio em banda estreita; pode travar em alguns aparelhos"
    ),

    /**
     * Perfil de midia (A2DP). Som em qualidade cheia no fone, microfone do
     * celular. Melhor para escutar, pior se o celular estiver no bolso.
     */
    MEDIA(
        "Midia",
        "som em qualidade cheia; usa o microfone do CELULAR, nao o do fone"
    ),

    /**
     * Tenta o perfil de chamada se o fone anunciar suporte; senao, midia.
     */
    AUTO(
        "Automatico",
        "usa chamada se o fone suportar, senao cai para midia"
    );

    companion object {
        fun from(name: String?): AudioMode =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}
