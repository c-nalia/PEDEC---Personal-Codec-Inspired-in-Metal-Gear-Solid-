package br.unesp.pedec.llm

/** Historico curto da conversa por radio. Guarda so os ultimos turnos. */
class Conversation(private val maxTurns: Int = 12) {

    data class Turn(val role: String, val text: String)

    private val turns = ArrayDeque<Turn>()

    fun user(text: String) = add(Turn("user", text))
    fun assistant(text: String) = add(Turn("assistant", text))

    private fun add(t: Turn) {
        turns.addLast(t)
        while (turns.size > maxTurns) turns.removeFirst()
        // a API exige que o historico comece com "user"
        while (turns.isNotEmpty() && turns.first().role != "user") turns.removeFirst()
    }

    fun snapshot(): List<Turn> = turns.toList()

    fun clear() = turns.clear()
}
