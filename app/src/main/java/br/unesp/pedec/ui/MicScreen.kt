package br.unesp.pedec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * Diagnostico do microfone.
 *
 * Existe porque dois detectores completamente diferentes — comparacao de
 * timbre e reconhecedor de fala — falharam do mesmo jeito. Quando isso
 * acontece, a causa quase nunca esta em nenhum dos dois: esta no audio que
 * chega a eles. Trocar por um terceiro detector sem verificar isso seria
 * repetir o erro pela terceira vez.
 *
 * Duas perguntas que esta tela responde e nenhum detector responde:
 *   1. O microfone esta captando? O medidor mostra o nivel ao vivo.
 *   2. O que ele capta e a sua voz? Gravar e ouvir de volta prova.
 */
@Composable
fun MicScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    val state by CodecBus.state.collectAsState()

    var source by remember { mutableStateOf(prefs.micSource) }
    var aec by remember { mutableStateOf(prefs.useAec) }
    var ns by remember { mutableStateOf(prefs.useNs) }

    fun aplicar() {
        prefs.micSource = source
        prefs.useAec = aec
        prefs.useNs = ns
        CodecService.reload(ctx)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "MICROFONE",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )

        Text(
            "Ligue a escuta no HUD e fale. Se a barra nao se mexer, o problema " +
                "e a captacao e nenhum detector vai funcionar. Se mexer mas o " +
                "reconhecimento falhar, ai sim o problema esta depois daqui.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        // ------------------------------------------------ medidor ao vivo
        val db = state.micDb
        // -90 dB = silencio digital, 0 dB = saturado. Fala normal fica
        // entre -35 e -15; abaixo de -60 e praticamente nada.
        val frac = ((db + 70f) / 70f).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .border(1.dp, if (state.micVoiced) Phosphor else PhosphorDim)
                .background(Panel)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(46.dp)
                    .background(if (state.micVoiced) Phosphor else PhosphorDim)
            )
            Text(
                "  %.0f dB   %s".format(db, if (state.micVoiced) "VOZ" else "-"),
                color = if (frac > 0.4f) Ink else Phosphor,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
        Text(
            when {
                db <= -85f -> "SILENCIO DIGITAL: nao chega nada. Confira a permissao " +
                    "de microfone e se outro app esta com ele preso."
                db <= -60f -> "muito baixo: pode ser processamento do fabricante comendo o sinal"
                db <= -12f -> "nivel util"
                else -> "alto demais, pode estar saturando"
            },
            color = if (db <= -60f) Danger else Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        Text(
            "voz: ${state.engine}",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )

        Spacer(Modifier.height(4.dp))

        // ------------------------------------------------ gravar e ouvir
        Text(
            "Gravar e ouvir",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Text(
            "Grava o que o microfone captar e toca de volta, cru, sem filtro " +
                "nenhum. Se voltar mudo ou irreconhecivel, o problema esta na " +
                "captacao e nao adianta trocar de detector.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        Button(
            onClick = { CodecService.micTest(ctx) },
            enabled = !state.capturing,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Amber,
                contentColor = Ink,
                disabledContainerColor = PhosphorDim,
                disabledContentColor = Ink
            )
        ) {
            Text(
                if (state.capturing) "FALE AGORA..." else "GRAVAR E OUVIR",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(4.dp))

        // ------------------------------------------------ fonte do microfone
        Text(
            "Fonte do microfone",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Text(
            "MIC e o cru, sem processamento do fabricante. RECONHECIMENTO e o " +
                "que o app usava, e em alguns aparelhos ele ja chega filtrado " +
                "demais. Se o medidor estiver baixo, comece trocando aqui.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        val fontes = listOf(1 to "MIC", 6 to "RECONH.", 7 to "CHAMADA", 0 to "PADRAO")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            fontes.forEach { (id, nome) ->
                val sel = id == source
                OutlinedButton(
                    onClick = { UiSfx.play(Sfx.UI_SELECT); source = id; aplicar() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (sel) Phosphor else Ink,
                        contentColor = if (sel) Ink else Phosphor
                    )
                ) { Text(nome, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            "Processamento do aparelho",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Text(
            "Os dois vinham LIGADOS sem opcao, e isso foi erro meu. O supressor " +
                "de ruido trata fala de baixa energia como ruido e pode apagar " +
                "a palavra inteira; o cancelador de eco chega a zerar o sinal. " +
                "Agora nascem desligados. So ligue se houver eco de verdade.",
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                Triple("ECO", aec) { v: Boolean -> aec = v },
                Triple("RUIDO", ns) { v: Boolean -> ns = v }
            ).forEach { (nome, valor, set) ->
                OutlinedButton(
                    onClick = { UiSfx.play(Sfx.UI_SELECT); set(!valor); aplicar() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (valor) Phosphor else Ink,
                        contentColor = if (valor) Ink else Phosphor
                    )
                ) {
                    Text(
                        "$nome ${if (valor) "ON" else "OFF"}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { UiSfx.play(Sfx.UI_SELECT); aplicar(); onBack() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("VOLTAR", color = Phosphor, fontFamily = FontFamily.Monospace)
        }

        Text(
            "Ordem sugerida: 1) medidor com MIC, eco e ruido desligados. " +
                "2) gravar e ouvir. 3) se a voz voltar limpa, o microfone esta " +
                "bom e o problema e do detector. 4) se voltar mudo ou picotado, " +
                "e captacao, e trocar de detector nao resolveria nada.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )

        Spacer(Modifier.height(24.dp))
    }
}
