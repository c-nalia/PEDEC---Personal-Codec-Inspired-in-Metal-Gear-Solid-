package br.unesp.pedec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.unesp.pedec.audio.Sfx
import br.unesp.pedec.audio.UiSfx
import br.unesp.pedec.data.Prefs
import br.unesp.pedec.service.CodecBus
import br.unesp.pedec.service.CodecService
import br.unesp.pedec.voice.VoiceCommands
import br.unesp.pedec.voice.WakeMatcher

/**
 * Calibracao da palavra de ativacao.
 *
 * O problema que isto resolve: o reconhecedor so escreve palavras que conhece,
 * e cada pessoa pronuncia de um jeito, entao nenhuma lista de grafias escrita a
 * priori acerta. Em vez de adivinhar, pedimos que voce fale algumas vezes e
 * anotamos o que o reconhecedor devolveu. Essas transcricoes viram grafias
 * aceitas por correspondencia exata — ou seja, passam por cima de qualquer
 * heuristica minha.
 */
/** O que esta sendo calibrado. */
enum class TrainTarget { WAKE, HANGUP }

@Composable
fun TrainScreen(target: TrainTarget, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    val state by CodecBus.state.collectAsState()
    var status by remember { mutableStateOf("") }

    // Sai do modo de calibracao se a tela for fechada de qualquer jeito,
    // inclusive pelo botao voltar do sistema.
    DisposableEffect(Unit) {
        CodecBus.clearSamples()
        onDispose { CodecService.trainEnd(ctx) }
    }

    val amostras = state.samples

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (target == TrainTarget.WAKE) "CALIBRAR ATIVACAO" else "CALIBRAR DESLIGAMENTO",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )

        Text(
            if (target == TrainTarget.WAKE) {
                "Toque em GRAVAR e diga a palavra de ativacao do jeito que voce " +
                    "vai falar no uso real. O que aparecer em cada linha e o que " +
                    "o reconhecedor entendeu, e e isso que vira gatilho."
            } else {
                "Toque em GRAVAR e diga a frase que deve DESLIGAR a chamada, " +
                    "por exemplo \"desliga\" ou \"cambio e desligo\". Repita " +
                    "algumas vezes. Lembre que o comando so vale quando for a " +
                    "fala inteira, entao perguntar sobre desligar algo continua " +
                    "sendo pergunta."
            },
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        // Estado da captura
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, if (state.capturing) Amber else PhosphorDim)
                .background(Panel)
                .padding(14.dp)
        ) {
            Text(
                when {
                    state.capturing && state.heard.isNotBlank() -> "~ ${state.heard}"
                    // Na primeira vez o modelo ainda esta sendo carregado; sem
                    // isso a tela dizia "pode falar" enquanto nada escutava.
                    state.capturing && state.engine.startsWith("carregando") ->
                        "carregando o modelo de voz..."
                    state.capturing -> "OUVINDO... pode falar"
                    amostras.isEmpty() -> "pronto para a primeira amostra"
                    else -> "${amostras.size} amostra(s) coletada(s)"
                },
                color = if (state.capturing) Amber else Phosphor,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
        }

        Button(
            onClick = {
                status = ""
                CodecService.trainCapture(ctx)
            },
            enabled = !state.capturing,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Phosphor,
                contentColor = Ink,
                disabledContainerColor = PhosphorDim,
                disabledContentColor = Ink
            )
        ) {
            Text(
                if (state.capturing) "GRAVANDO..." else "GRAVAR AMOSTRA",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        // Lista de amostras
        amostras.forEachIndexed { i, amostra ->
            val vazia = amostra.isBlank()
            val arriscada = !vazia && target == TrainTarget.WAKE && WakeMatcher.isRisky(amostra)
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (vazia) Danger else PhosphorDim)
                    .background(Panel)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.fillMaxWidth(0.75f)) {
                    Text(
                        if (vazia) "(nada captado)" else amostra,
                        color = if (vazia) Danger else Phosphor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                    if (arriscada) {
                        Text(
                            "palavra comum: pode ativar sozinho numa conversa",
                            color = Amber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    }
                }
                TextButton(onClick = {
                    UiSfx.play(Sfx.UI_SELECT)
                    CodecBus.dropSample(i)
                }) {
                    Text("x", color = Danger, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                }
            }
        }

        if (amostras.count { it.isNotBlank() } >= 2) {
            val distintas = amostras.filter { it.isNotBlank() }
                .map { WakeMatcher.normalize(it) }.distinct()
            Text(
                if (distintas.size == 1) {
                    "Boa: o reconhecedor foi consistente. Uma grafia so ja resolve."
                } else {
                    "${distintas.size} grafias diferentes. Todas viram gatilho, " +
                        "entao a variacao trabalha a seu favor."
                },
                color = PhosphorDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = {
                val uteis = amostras.filter { it.isNotBlank() }
                if (uteis.isEmpty()) {
                    status = "nenhuma amostra util para salvar"
                    return@Button
                }
                val lista = if (target == TrainTarget.WAKE) {
                    WakeMatcher.mergeLearned(uteis, keepDefaults = true)
                } else {
                    VoiceCommands.mergeLearned(uteis, keepDefaults = true)
                }
                if (target == TrainTarget.WAKE) {
                    prefs.wakePhrases = lista.joinToString(", ")
                } else {
                    prefs.hangUpPhrases = lista.joinToString(", ")
                }
                UiSfx.play(Sfx.UI_CONFIRM)
                // Aplica no motor sem reiniciar o servico.
                CodecService.reload(ctx)
                status = "salvo: ${lista.size} grafias aceitas"
            },
            enabled = amostras.any { it.isNotBlank() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Amber,
                contentColor = Ink,
                disabledContainerColor = PhosphorDim,
                disabledContentColor = Ink
            )
        ) {
            Text(
                "SALVAR CALIBRACAO",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        if (status.isNotBlank()) {
            Text(status, color = Amber, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        OutlinedButton(
            onClick = {
                UiSfx.play(Sfx.UI_SELECT)
                onBack()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("VOLTAR", color = Phosphor, fontFamily = FontFamily.Monospace)
        }

        Text(
            if (target == TrainTarget.WAKE) {
                "Depois de salvar, volte ao HUD e fale a palavra: o painel VOCE " +
                    "mostra em tempo real o que ele entende, com um ~ na frente. " +
                    "Se ainda escapar, grave mais amostras — elas se somam as " +
                    "anteriores."
            } else {
                "Depois de salvar, abra uma chamada e diga a frase: ela deve " +
                    "encerrar sem chegar ao modelo. As amostras se somam as " +
                    "frases que ja existiam."
            },
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )

        Spacer(Modifier.height(24.dp))
    }
}
