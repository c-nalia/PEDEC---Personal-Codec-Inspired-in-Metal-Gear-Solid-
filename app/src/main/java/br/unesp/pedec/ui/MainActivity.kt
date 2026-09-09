package br.unesp.pedec.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import br.unesp.pedec.audio.Sfx
import br.unesp.pedec.audio.UiSfx
import br.unesp.pedec.data.Prefs
import br.unesp.pedec.service.CodecBus
import br.unesp.pedec.service.CodecService
import br.unesp.pedec.service.Phase

/**
 * HUD simples. So aparece quando voce abre o app na mao; a chamada por voz
 * roda inteira sem tela.
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        UiSfx.init(this)
        UiSfx.volume = Prefs.get(this).sfxVolume
        setContent {
            PedecTheme {
                var screen by remember { mutableStateOf("hud") }
                // A partir do targetSdk 35 o Android 15 desenha de borda a
                // borda por padrao: sem consumir os insets, o topo fica sob a
                // barra de status e os botoes de baixo sob a barra de
                // navegacao. Era isso que fazia o app "nao caber na tela".
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Ink)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .imePadding()
                ) {
                    // Atras de tudo, sem interceptar toque: o Box seguinte
                    // desenha por cima e recebe os gestos normalmente.
                    FundoCodec()

                    when (screen) {
                        "config" -> SettingsScreen(onBack = {
                            UiSfx.play(Sfx.UI_SELECT)
                            screen = "hud"
                        })
                        "train" -> TrainScreen(TrainTarget.WAKE) { screen = "hud" }
                        "trainoff" -> TrainScreen(TrainTarget.HANGUP) { screen = "hud" }
                        "mic" -> MicScreen { screen = "hud" }
                        "memoria" -> MemoriaScreen { screen = "hud" }
                        else -> Hud(
                            onConfig = {
                                UiSfx.play(Sfx.UI_OPEN)
                                screen = "config"
                            },
                            onTrain = {
                                UiSfx.play(Sfx.UI_OPEN)
                                screen = "train"
                            },
                            onTrainHangUp = {
                                UiSfx.play(Sfx.UI_OPEN)
                                screen = "trainoff"
                            },
                            onMic = {
                                UiSfx.play(Sfx.UI_OPEN)
                                screen = "mic"
                            },
                            onMemoria = {
                                UiSfx.play(Sfx.UI_OPEN)
                                screen = "memoria"
                            }
                        )
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()
        fun check(p: String) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed += p
            }
        }
        check(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            check(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            check(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}

@Composable
private fun Hud(
    onConfig: () -> Unit,
    onTrain: () -> Unit,
    onTrainHangUp: () -> Unit,
    onMic: () -> Unit,
    onMemoria: () -> Unit
) {
    val ctx = LocalContext.current
    val state by CodecBus.state.collectAsState()
    val prefs = remember { Prefs.get(ctx) }
    val running = state.phase != Phase.OFF

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "PERSONAL CODEC",
                color = Phosphor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp
            )
            Text(
                if (state.headset) "SCO/A2DP" else "ALTO-FALANTE",
                color = PhosphorDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }

        StatusBar(state.phase)

        // Area rolavel. Antes o Column inteiro era rigido: resposta longa
        // empurrava os botoes para fora da tela e a CONFIGURACAO ficava
        // inalcancavel. Agora o texto rola e os botoes ficam ancorados abaixo.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

        TerminalPanel(title = "VOCE") {
            // Em espera, mostra o que o reconhecedor esta entendendo. E assim
            // que voce descobre qual grafia de "Pedec" acrescentar na lista.
            val ouvido = when {
                state.transcript.isNotBlank() -> state.transcript
                state.heard.isNotBlank() -> "~ ${state.heard}"
                else -> "..."
            }
            Text(
                ouvido,
                color = if (state.transcript.isBlank() && state.heard.isNotBlank()) {
                    PhosphorDim
                } else {
                    Phosphor
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp
            )
        }

        // Mostrador de frequencia, entre as duas caixas de dialogo. As barras
        // seguem o nivel do microfone, entao ele tambem serve de diagnostico:
        // se voce fala e nada se move, a captacao e que esta falhando.
        CodecFrequencyPanel(
            frequencia = "140.85",
            micDb = state.micDb,
            voiced = state.micVoiced
        )

        // Sem rolagem propria aqui: duas rolagens na mesma direcao brigam
        // pelo gesto. O painel cresce e quem rola e o container de cima.
        TerminalPanel(title = "CODEC", modifier = Modifier.heightIn(min = 120.dp)) {
            Text(
                state.response.ifBlank { "..." },
                color = Phosphor,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp
            )
        }

        if (state.error.isNotBlank()) {
            Text(
                "! ${state.error}",
                color = Danger,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }

        Text(
            buildString {
                append(if (state.headset) "fone: conectado" else "fone: nenhum")
                append("   |   voz: ")
                append(state.engine)
                append("   |   audio: ")
                append(state.audio)
            },
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        }
        // ------------------------------------------- fim da area rolavel

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = {
                if (running) {
                    CodecService.stop(ctx)
                } else {
                    UiSfx.play(Sfx.UI_CONFIRM)
                    CodecService.start(ctx)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) Danger else Phosphor,
                contentColor = Ink
            )
        ) {
            Text(
                if (running) "DESLIGAR ESCUTA" else "LIGAR ESCUTA",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedButton(
            onClick = {
                UiSfx.play(Sfx.ALERT)
                CodecService.trigger(ctx)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("CHAMAR AGORA", fontFamily = FontFamily.Monospace, color = Phosphor)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onTrain, modifier = Modifier.weight(1f)) {
                Text(
                    "CALIBRAR ATIV.",
                    fontFamily = FontFamily.Monospace,
                    color = Amber,
                    fontSize = 12.sp
                )
            }
            OutlinedButton(onClick = onTrainHangUp, modifier = Modifier.weight(1f)) {
                Text(
                    "CALIBRAR DESLIG.",
                    fontFamily = FontFamily.Monospace,
                    color = Amber,
                    fontSize = 12.sp
                )
            }
        }

        OutlinedButton(onClick = onMemoria, modifier = Modifier.fillMaxWidth()) {
            Text("MEMORIA", fontFamily = FontFamily.Monospace, color = Phosphor)
        }

        OutlinedButton(onClick = onMic, modifier = Modifier.fillMaxWidth()) {
            Text("TESTAR MICROFONE", fontFamily = FontFamily.Monospace, color = Danger)
        }

        OutlinedButton(onClick = onConfig, modifier = Modifier.fillMaxWidth()) {
            Text("CONFIGURACAO", fontFamily = FontFamily.Monospace, color = Phosphor)
        }

        if (!prefs.isConfigured) {
            Text(
                "Configure as chaves antes de ligar a escuta.",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
        if (prefs.wakePhrases.isBlank()) {
            Text(
                "Nenhuma palavra de ativacao definida: use CALIBRAR ATIV. ou " +
                    "escreva na configuracao. Ate la, so CHAMAR AGORA abre a linha.",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun StatusBar(phase: Phase) {
    val (label, color) = when (phase) {
        Phase.OFF -> "OFFLINE" to PhosphorDim
        Phase.STANDBY -> "AGUARDANDO  \"PEDEC\"" to PhosphorDim
        Phase.RINGING -> "CHAMANDO..." to Amber
        Phase.LISTENING -> "OUVINDO" to Phosphor
        Phase.THINKING -> "PROCESSANDO" to Amber
        Phase.SPEAKING -> "TRANSMITINDO" to Phosphor
        Phase.ERROR -> "FALHA NA LINHA" to Danger
    }
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, color)
            .background(Panel)
            .padding(10.dp)
    ) {
        Text(label, color = color, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
}

@Composable
private fun TerminalPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, PanelBorda)
            .background(Panel)
            .padding(10.dp)
    ) {
        Text(title, color = PhosphorDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}
