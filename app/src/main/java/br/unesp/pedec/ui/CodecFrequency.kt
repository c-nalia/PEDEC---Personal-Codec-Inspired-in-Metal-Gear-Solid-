package br.unesp.pedec.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow

/**
 * O painel de frequencia do codec, entre as duas caixas de dialogo.
 *
 * A referencia e a tela de MEMORY do Metal Gear Solid: cabecalho PTT, rodape
 * MEMORY, setas nas laterais e um mostrador de barras no meio.
 *
 * As barras nao sao enfeite animado em loop. Elas seguem o NIVEL REAL do
 * microfone, que o servico ja publica em CodecBus.micDb para a tela de
 * diagnostico. Assim o painel responde quando voce fala e fica parado quando
 * ha silencio — vira instrumento, nao papel de parede, e de quebra voce
 * enxerga na tela principal se o microfone esta captando.
 */
@Composable
fun CodecFrequencyPanel(
    frequencia: String,
    micDb: Float,
    voiced: Boolean,
    modifier: Modifier = Modifier,
    onAnterior: () -> Unit = {},
    onProxima: () -> Unit = {}
) {
    // -90 dB e silencio digital, -10 e alto. Normaliza para 0..1.
    val alvo = ((micDb + 70f) / 60f).coerceIn(0f, 1f)
    // A animacao suaviza o salto entre quadros; sem ela o mostrador pisca.
    val nivel by animateFloatAsState(
        targetValue = alvo,
        animationSpec = tween(durationMillis = 120),
        label = "nivel"
    )

    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, PanelBorda)
            .background(
                // Verde translucido em degrade, com a luz vindo de cima:
                // e o que da a impressao de tubo de raios catodicos aceso por
                // tras do vidro, em vez de um retangulo chapado.
                Brush.verticalGradient(
                    listOf(
                        Color(0x2E7CFF6B),
                        Color(0x140A120A),
                        Color(0x2233FF88)
                    )
                )
            )
    ) {
        Etiqueta("P T T")

        Row(
            Modifier
                .fillMaxWidth()
                .height(86.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Seta("◀", onAnterior)

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(vertical = 6.dp)
                    .border(1.dp, Color(0x3D7CFF6B))
                    .background(Color(0x140A120A))
            ) {
                Mostrador(nivel, voiced)

                Text(
                    frequencia,
                    color = if (voiced) Phosphor else Amber,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 14.dp)
                )
            }

            Seta("▶", onProxima)
        }

        Etiqueta("M E M O R Y")
    }
}

/**
 * As barras verticais.
 *
 * O desenho e uma curva crescente da esquerda para a direita, como no jogo, e
 * o nivel do microfone levanta o conjunto todo. Cada barra tem um peso fixo
 * proprio (a potencia abaixo), entao as da direita reagem mais — o que da a
 * silhueta em rampa mesmo com o sinal variando.
 */
@Composable
private fun Mostrador(nivel: Float, voiced: Boolean) {
    Canvas(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        val barras = 22
        val vao = size.width / barras
        val largura = vao * 0.55f
        val corViva = if (voiced) Color(0xFF7CFF6B) else Color(0xFF3E8A38)

        for (i in 0 until barras) {
            val t = i / (barras - 1f)
            // Rampa: as barras da direita crescem mais que as da esquerda.
            val peso = t.toDouble().pow(1.6).toFloat()
            // Um piso pequeno mantem o mostrador visivel no silencio, como o
            // ruido de fundo de um radio ligado.
            val altura = (0.10f + peso * nivel * 0.90f).coerceIn(0.05f, 1f)
            val h = size.height * altura

            drawRect(
                color = corViva.copy(alpha = 0.25f + 0.65f * altura),
                topLeft = Offset(i * vao + (vao - largura) / 2f, size.height - h),
                size = Size(largura, h)
            )
        }

        // Linhas de varredura. Duas por barra seriam demais; a cada 4 px o
        // efeito ja aparece sem virar textura pesada.
        var y = 0f
        while (y < size.height) {
            drawRect(
                color = Color(0x14000000),
                topLeft = Offset(0f, y),
                size = Size(size.width, 1f)
            )
            y += 4f
        }
    }
}

@Composable
private fun Etiqueta(texto: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0x2633FF88))
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            texto,
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun Seta(simbolo: String, onClique: () -> Unit) {
    Box(
        Modifier
            .width(34.dp)
            .fillMaxSize()
            .clickable { chamarComSeguranca(onClique) },
        contentAlignment = Alignment.Center
    ) {
        Text(simbolo, color = PhosphorDim, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
}

/** Chama a acao sem deixar uma excecao dela derrubar a composicao. */
private fun chamarComSeguranca(acao: () -> Unit) {
    runCatching { acao() }
}

/**
 * Fundo verde translucido da tela toda.
 *
 * Vai atras do conteudo, nunca na frente: um brilho difuso no alto, como o
 * fosforo de um monitor velho, e varredura horizontal por cima. A opacidade e
 * baixa de proposito — o objetivo e ambientar, e texto sobre fundo texturizado
 * cansa a vista rapido.
 */
@Composable
fun FundoCodec(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x2633FF88), Color(0x0D1FA05A), Color(0x00000000)),
                center = Offset(size.width * 0.5f, size.height * 0.18f),
                radius = size.maxDimension * 0.85f
            )
        )
        var y = 0f
        while (y < size.height) {
            drawRect(
                color = Color(0x0F000000),
                topLeft = Offset(0f, y),
                size = Size(size.width, 1.4f)
            )
            y += 4f
        }
        // Vinheta discreta nas bordas, para o centro parecer mais aceso.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x00000000), Color(0x66000000)),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.maxDimension * 0.62f
            )
        )
    }
}
