package br.unesp.pedec.llm

/**
 * Contrato unico para os provedores. O servico nao sabe com quem esta falando:
 * pede um texto em streaming e recebe pedacos conforme chegam.
 */
interface LlmClient {
    /**
     * @param onDelta recebe cada pedaco de texto novo.
     * @param buscar quando nao nulo, a busca na web e oferecida ao modelo como
     *   ferramenta. Recebe a consulta e devolve o resultado ja em texto. E
     *   chamada na mesma thread do streaming, entao deve ser rapida — quem
     *   estiver do outro lado esta esperando em silencio.
     * @return o texto completo da resposta.
     */
    fun stream(
        systemPrompt: String,
        history: List<Conversation.Turn>,
        buscar: ((String) -> String)? = null,
        onDelta: (String) -> Unit
    ): String
}

/**
 * A ferramenta de busca, descrita uma vez so.
 *
 * Os dois formatos de API — Anthropic e OpenAI — envelopam isto de maneiras
 * diferentes, mas o nome, a descricao e o esquema sao os mesmos. Mante-los
 * aqui evita que as duas implementacoes descrevam a mesma ferramenta de jeitos
 * sutilmente diferentes e o modelo se comporte diferente conforme o provedor.
 */
object Ferramentas {
    const val BUSCA = "buscar_na_web"

    const val DESCRICAO =
        "Pesquisa na web e devolve resultados atuais. Use quando a pergunta " +
        "depender de informacao recente, especifica ou que voce nao tenha " +
        "certeza: noticias, precos, resultados, versoes de software, eventos, " +
        "qualquer coisa posterior ao seu treinamento. Nao use para conhecimento " +
        "geral estavel nem para conversa comum."

    const val PARAM = "consulta"

    const val PARAM_DESCRICAO =
        "O que pesquisar, em poucas palavras, como voce digitaria num buscador."
}

/**
 * Provedores conhecidos. Cada um traz a URL e o modelo padrao, para a tela de
 * configuracao preencher sozinha e voce so colar a chave.
 *
 * Todos, menos a Anthropic, falam o formato de chat da OpenAI. E por isso que
 * um cliente so atende a lista inteira: muda a URL base, o modelo e a chave.
 */
enum class Provider(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val needsKey: Boolean,
    val hint: String
) {
    ANTHROPIC(
        label = "Claude",
        baseUrl = "https://api.anthropic.com",
        defaultModel = "claude-sonnet-5",
        needsKey = true,
        hint = "console.anthropic.com — pago, precisa de credito"
    ),

    GEMINI(
        label = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        // O Google aposenta modelo rapido. Se der 404 dizendo que o modelo nao
        // esta mais disponivel, troque aqui pelo atual: os leves da vez sao
        // gemini-3.6-flash e gemini-3.5-flash-lite. A lista viva esta em
        // ai.google.dev/gemini-api/docs/models
        defaultModel = "gemini-3.6-flash",
        needsKey = true,
        hint = "aistudio.google.com/apikey — gratuito, sem cartao"
    ),

    GROQ(
        label = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.3-70b-versatile",
        needsKey = true,
        hint = "console.groq.com/keys — gratuito, o mais rapido"
    ),

    OPENROUTER(
        label = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "meta-llama/llama-3.3-70b-instruct:free",
        needsKey = true,
        hint = "openrouter.ai/keys — varios modelos com sufixo :free"
    ),

    CEREBRAS(
        label = "Cerebras",
        baseUrl = "https://api.cerebras.ai/v1",
        defaultModel = "llama-3.3-70b",
        needsKey = true,
        hint = "cloud.cerebras.ai — gratuito, altissima vazao"
    ),

    OLLAMA(
        label = "Ollama",
        // Endereco de exemplo. Troque pelo IP do SEU computador na rede local,
        // que voce descobre com ipconfig no Windows ou ip addr no Linux.
        baseUrl = "http://192.168.1.100:11434/v1",
        defaultModel = "llama3.1:8b",
        needsKey = false,
        hint = "seu PC na rede local: troque o IP e rode o Ollama com OLLAMA_HOST=0.0.0.0"
    );

    val isAnthropic: Boolean get() = this == ANTHROPIC

    companion object {
        fun from(name: String?): Provider =
            entries.firstOrNull { it.name == name } ?: GEMINI
    }
}
