package br.unesp.pedec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
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
import br.unesp.pedec.data.Memoria
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Revisao da memoria.
 *
 * Existe porque memoria automatica sem tela de revisao e caixa-preta: o
 * assistente passa a se comportar com base em coisas que voce nao sabe que ele
 * guardou, e quando ele erra voce nao tem como descobrir por que. Aqui voce ve
 * cada fato e apaga o que estiver errado ou velho.
 */
@Composable
fun MemoriaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val memoria = remember { Memoria.get(ctx) }

    // A lista vive no arquivo, nao no Compose. Este contador existe so para
    // forcar a recomposicao depois de apagar algo.
    var versao by remember { mutableStateOf(0) }
    val fatos = remember(versao) { memoria.listar() }
    val formato = remember { SimpleDateFormat("dd/MM/yy", Locale("pt", "BR")) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "MEMORIA",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )

        Text(
            "Ao fim de cada chamada, o codec separa o que vale a pena lembrar " +
                "sobre voce e guarda aqui, so no aparelho. Isso entra no prompt " +
                "das conversas seguintes, entao ele nao recomeca do zero toda vez.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        Text(
            "${fatos.size} de ${Memoria.LIMITE_FATOS} lembrancas",
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        Spacer(Modifier.height(4.dp))

        if (fatos.isEmpty()) {
            Text(
                "Nada guardado ainda. Converse algumas vezes e volte aqui.",
                color = PhosphorDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }

        fatos.forEachIndexed { i, fato ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, PanelBorda)
                    .background(Panel)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        fato.texto,
                        color = Phosphor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                    Text(
                        formato.format(Date(fato.quando)),
                        color = PhosphorDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }
                OutlinedButton(
                    onClick = {
                        UiSfx.play(Sfx.UI_SELECT)
                        memoria.esquecer(i)
                        versao++
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Ink,
                        contentColor = Danger
                    )
                ) {
                    Text("X", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (fatos.isNotEmpty()) {
            Button(
                onClick = {
                    UiSfx.play(Sfx.UI_CONFIRM)
                    memoria.limpar()
                    versao++
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Danger,
                    contentColor = Ink
                )
            ) {
                Text(
                    "APAGAR TUDO",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        OutlinedButton(
            onClick = { UiSfx.play(Sfx.UI_SELECT); onBack() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("VOLTAR", color = Phosphor, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(24.dp))
    }
}
