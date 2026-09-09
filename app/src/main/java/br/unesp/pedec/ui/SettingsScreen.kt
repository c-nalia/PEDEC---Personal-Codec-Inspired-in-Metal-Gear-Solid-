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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.media.AudioAttributes
import br.unesp.pedec.audio.AudioMode
import br.unesp.pedec.audio.PcmPlayer
import br.unesp.pedec.audio.Sfx
import br.unesp.pedec.audio.SfxBank
import br.unesp.pedec.audio.UiSfx
import br.unesp.pedec.data.Prefs
import br.unesp.pedec.llm.Provider
import br.unesp.pedec.stt.SttEngine
import br.unesp.pedec.tts.ElevenLabsTts
import br.unesp.pedec.tts.SystemTts
import br.unesp.pedec.tts.TtsEngine
import br.unesp.pedec.tts.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(prefs.provider) }
    var llmKey by remember { mutableStateOf(prefs.key(prefs.provider)) }
    var llmModel by remember { mutableStateOf(prefs.model(prefs.provider)) }
    var llmUrl by remember { mutableStateOf(prefs.baseUrl(prefs.provider)) }
    var eleven by remember { mutableStateOf(prefs.elevenKey) }
    var wakePhrases by remember { mutableStateOf(prefs.wakePhrases) }
    var effort by remember { mutableStateOf(prefs.effort) }
    var audioMode by remember { mutableStateOf(prefs.audioMode) }
    var ttsEngine by remember { mutableStateOf(prefs.ttsEngine) }
    var androidVoice by remember { mutableStateOf(prefs.androidVoice) }
    var speechRate by remember { mutableStateOf(prefs.speechRate) }
    var speechPitch by remember { mutableStateOf(prefs.speechPitch) }
    var endSilence by remember { mutableStateOf(prefs.endSilenceMs.toFloat()) }
    var sfxMinimal by remember { mutableStateOf(prefs.sfxMinimal) }
    var bargeIn by remember { mutableStateOf(prefs.bargeIn) }
    var sttEngine by remember { mutableStateOf(prefs.sttEngine) }
    var sttOffline by remember { mutableStateOf(prefs.sttPreferOffline) }
    var musicEnabled by remember { mutableStateOf(prefs.musicEnabled) }
    var musicShortcuts by remember { mutableStateOf(prefs.musicShortcuts) }
    var hangUp by remember { mutableStateOf(prefs.hangUpPhrases) }
    val localVoices = remember { mutableStateListOf<SystemTts.VoiceOption>() }
    var ttsModel by remember { mutableStateOf(prefs.ttsModel) }
    var systemPrompt by remember { mutableStateOf(prefs.systemPrompt) }
    var braveKey by remember { mutableStateOf(prefs.braveKey) }
    var webSearch by remember { mutableStateOf(prefs.webSearch) }
    var memoriaAtiva by remember { mutableStateOf(prefs.memoriaAtiva) }
    var radioMix by remember { mutableStateOf(prefs.radioMix) }
    var followUp by remember { mutableStateOf(prefs.followUp) }
    var autoStart by remember { mutableStateOf(prefs.autoStart) }
    var sfxFull by remember { mutableStateOf(prefs.sfxFull) }
    var sfxVolume by remember { mutableStateOf(prefs.sfxVolume) }
    var voiceId by remember { mutableStateOf(prefs.voiceId) }
    var voiceName by remember { mutableStateOf(prefs.voiceName) }

    val voices = remember { mutableStateListOf<Voice>() }
    var status by remember { mutableStateOf("") }

    fun save() {
        prefs.provider = provider
        prefs.setKey(provider, llmKey)
        prefs.setModel(provider, llmModel)
        prefs.setBaseUrl(provider, llmUrl)
        prefs.elevenKey = eleven.trim()
        prefs.wakePhrases = wakePhrases.trim()
        prefs.effort = effort.trim()
        prefs.audioMode = audioMode
        prefs.ttsEngine = ttsEngine
        prefs.androidVoice = androidVoice
        prefs.speechRate = speechRate
        prefs.speechPitch = speechPitch
        prefs.endSilenceMs = endSilence.toInt()
        prefs.sfxMinimal = sfxMinimal
        prefs.bargeIn = bargeIn
        prefs.sttEngine = sttEngine
        prefs.sttPreferOffline = sttOffline
        prefs.musicEnabled = musicEnabled
        prefs.musicShortcuts = musicShortcuts
        prefs.hangUpPhrases = hangUp.trim()
        prefs.ttsModel = ttsModel.trim()
        prefs.systemPrompt = systemPrompt
        prefs.braveKey = braveKey.trim()
        prefs.webSearch = webSearch
        prefs.memoriaAtiva = memoriaAtiva
        prefs.radioMix = radioMix
        prefs.followUp = followUp
        prefs.sfxFull = sfxFull
        prefs.sfxVolume = sfxVolume
        UiSfx.volume = sfxVolume
        prefs.autoStart = autoStart
        prefs.voiceId = voiceId
        prefs.voiceName = voiceName
    }

    // Guarda o que estava sendo editado antes de trocar, para voce nao perder
    // a chave do provedor anterior ao experimentar outro.
    fun switchTo(p: Provider) {
        prefs.setKey(provider, llmKey)
        prefs.setModel(provider, llmModel)
        prefs.setBaseUrl(provider, llmUrl)
        provider = p
        llmKey = prefs.key(p)
        llmModel = prefs.model(p)
        llmUrl = prefs.baseUrl(p)
        prefs.provider = p
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
            "CONFIGURACAO",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )

        Text(
            "Provedor do modelo",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )

        // Seis provedores em duas fileiras. Cada botao ja preenche URL e modelo
        // padrao; so falta colar a chave.
        Provider.entries.toList().chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { p ->
                    val selected = p == provider
                    OutlinedButton(
                        onClick = { UiSfx.play(Sfx.UI_SELECT); switchTo(p) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) Phosphor else Ink,
                            contentColor = if (selected) Ink else Phosphor
                        )
                    ) {
                        Text(p.label, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Text(
            provider.hint,
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        if (provider.needsKey) {
            Field("Chave da API", llmKey, secret = true) { llmKey = it }
        } else {
            Text(
                "Este provedor nao usa chave.",
                color = PhosphorDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }

        Field("Modelo", llmModel) { llmModel = it }

        // A URL da Anthropic e fixa no cliente dela; para os demais vale editar,
        // sobretudo no Ollama, onde muda o IP do seu PC.
        if (!provider.isAnthropic) {
            Field("URL base", llmUrl) { llmUrl = it }
        }

        run {
            Text(
                "Esforco de raciocinio",
                color = PhosphorDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Text(
                "Modelos recentes pensam antes de emitir a primeira palavra. " +
                    "Para resposta falada curta, low e o que faz a voz comecar " +
                    "quase junto com a pergunta.",
                color = PhosphorDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("low", "medium", "high", "xhigh").forEach { level ->
                    val selected = effort == level
                    OutlinedButton(
                        onClick = { UiSfx.play(Sfx.UI_SELECT); effort = level },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) Phosphor else Ink,
                            contentColor = if (selected) Ink else Phosphor
                        )
                    ) { Text(level, fontFamily = FontFamily.Monospace) }
                }
            }
        }

        Divider()

        Text(
            "Voz da resposta",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TtsEngine.entries.forEach { e ->
                val selected = e == ttsEngine
                OutlinedButton(
                    onClick = { UiSfx.play(Sfx.UI_SELECT); ttsEngine = e },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) Phosphor else Ink,
                        contentColor = if (selected) Ink else Phosphor
                    )
                ) { Text(e.label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
        Text(
            ttsEngine.hint,
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        if (ttsEngine == TtsEngine.ANDROID) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Voz: ${androidVoice.ifBlank { "padrao do sistema" }}",
                    color = Phosphor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(0.7f)
                )
                TextButton(onClick = {
                    scope.launch {
                        status = "lendo vozes do aparelho..."
                        // Criado aqui, na main thread; so a leitura das
                        // vozes vai para IO.
                        // O try/finally NAO e formalidade: o TextToSpeech
                        // segura uma conexao com o servico de voz do sistema.
                        // Sem release, cada toque neste botao vazava uma
                        // conexao que so morria junto com o processo.
                        val tts = SystemTts(ctx)
                        val list = try {
                            withContext(Dispatchers.IO) { tts.voices() }
                        } catch (e: Exception) {
                            status = "falha ao ler as vozes: ${e.message}"
                            emptyList()
                        } finally {
                            runCatching { tts.release() }
                        }
                        localVoices.clear()
                        localVoices.addAll(list)
                        status = if (list.isEmpty()) {
                            "nenhuma voz pt encontrada"
                        } else {
                            "${list.size} vozes"
                        }
                    }
                }) { Text("listar", color = Amber, fontFamily = FontFamily.Monospace) }
            }

            localVoices.forEach { v ->
                val selected = v.id == androidVoice
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (selected) Phosphor else PhosphorDim)
                        .background(Panel)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        v.label,
                        color = if (selected) Amber else Phosphor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier.fillMaxWidth(0.55f)
                    )
                    Row {
                        TextButton(onClick = {
                            UiSfx.play(Sfx.UI_SELECT)
                            androidVoice = v.id
                        }) { Text("usar", color = Phosphor, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                        TextButton(onClick = {
                            scope.launch {
                                status = "testando..."
                                withContext(Dispatchers.IO) {
                                    val player = PcmPlayer(22050, previewUsage(audioMode))
                                    val tts = SystemTts(ctx)
                                    try {
                                        tts.speakFiltered(
                                            "Codec pessoal. Linha aberta, pode falar.",
                                            player, radioMix, v.id, speechRate, speechPitch
                                        )
                                    } finally {
                                        // Os dois liberam mesmo se a sintese
                                        // estourar no meio. O Android limita
                                        // quantos AudioTrack existem por
                                        // processo: alguns testes falhos
                                        // seguidos derrubavam a fala do app.
                                        runCatching { player.finish() }
                                        runCatching { tts.release() }
                                    }
                                }
                                status = "teste concluido"
                            }
                        }) { Text("ouvir", color = Amber, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    }
                }
            }

            Text(
                "Velocidade: ${"%.2f".format(speechRate)}x",
                color = Phosphor, fontFamily = FontFamily.Monospace, fontSize = 12.sp
            )
            Slider(value = speechRate, onValueChange = { speechRate = it }, valueRange = 0.7f..1.6f)

            Text(
                "Tom: ${"%.2f".format(speechPitch)}   (mais grave combina com o codec)",
                color = Phosphor, fontFamily = FontFamily.Monospace, fontSize = 12.sp
            )
            Slider(value = speechPitch, onValueChange = { speechPitch = it }, valueRange = 0.6f..1.3f)
        }

        Divider()

        Field("Chave da API ElevenLabs", eleven, secret = true) { eleven = it }
        Field("Modelo de voz", ttsModel) { ttsModel = it }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Voz: ${voiceName.ifBlank { "nenhuma" }}",
                color = Phosphor,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
            TextButton(onClick = {
                scope.launch {
                    status = "buscando vozes..."
                    // rememberCoroutineScope nao tem tratador de excecao: uma
                    // falha de rede aqui subia sem ninguem pegar e derrubava o
                    // app inteiro a partir da tela de ajustes.
                    val list = try {
                        withContext(Dispatchers.IO) { ElevenLabsTts.listVoices(eleven.trim()) }
                    } catch (e: Exception) {
                        status = "falha ao buscar vozes: ${e.message}"
                        return@launch
                    }
                    voices.clear()
                    voices.addAll(list)
                    status = if (list.isEmpty()) "nenhuma voz encontrada (confira a chave)" else "${list.size} vozes"
                }
            }) { Text("carregar", color = Amber, fontFamily = FontFamily.Monospace) }
        }

        voices.forEach { v ->
            val selected = v.id == voiceId
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (selected) Phosphor else PhosphorDim)
                    .background(Panel)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.fillMaxWidth(0.6f)) {
                    Text(v.name, color = Phosphor, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    if (v.labels.isNotBlank()) {
                        Text(v.labels, color = PhosphorDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
                Row {
                    TextButton(onClick = {
                        UiSfx.play(Sfx.UI_SELECT)
                        voiceId = v.id
                        voiceName = v.name
                        save()
                    }) { Text("usar", color = if (selected) Amber else Phosphor, fontFamily = FontFamily.Monospace) }
                    TextButton(onClick = {
                        scope.launch {
                            status = "testando ${v.name}..."
                            val err = withContext(Dispatchers.IO) {
                                val player = PcmPlayer(22050, previewUsage(audioMode))
                                try {
                                    SfxBank.render(ctx, Sfx.OPEN, sfxFull, sfxVolume)
                                        ?.let { player.playAndWait(it) }
                                    ElevenLabsTts(eleven.trim(), v.id, ttsModel.trim(), radioMix)
                                        .speak(
                                            "Codec pessoal. Linha aberta, pode falar.",
                                            player
                                        )
                                    player.finish()
                                    null
                                } catch (e: Exception) {
                                    // stopNow cala mas NAO libera, por design.
                                    // Aqui somos o dono do player, entao a
                                    // liberacao e nossa.
                                    player.stopNow()
                                    runCatching { player.finish() }
                                    e.message
                                }
                            }
                            status = err ?: "teste concluido"
                        }
                    }) { Text("ouvir", color = Amber, fontFamily = FontFamily.Monospace) }
                }
            }
        }

        Divider()

        Text(
            "Quem transcreve sua pergunta",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SttEngine.entries.forEach { e ->
                val selected = e == sttEngine
                OutlinedButton(
                    onClick = { UiSfx.play(Sfx.UI_SELECT); sttEngine = e },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) Phosphor else Ink,
                        contentColor = if (selected) Ink else Phosphor
                    )
                ) { Text(e.label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
        Text(
            sttEngine.hint,
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Text(
            "A palavra de ativacao continua sempre no Vosk, offline. So a " +
                "pergunta muda de motor.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )

        if (sttEngine == SttEngine.ANDROID) {
            Toggle("Forcar reconhecimento offline do Android", sttOffline) {
                UiSfx.play(Sfx.UI_SELECT)
                sttOffline = it
            }
            Text(
                "Exige o pacote pt-BR baixado em Configuracoes do sistema, " +
                    "Idiomas, Reconhecimento de voz. Se der erro de rede com " +
                    "isto ligado, o pacote nao esta instalado.",
                color = PhosphorDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }

        Divider()

        // ------------------------------------------------------ busca e memoria
        Text(
            "Busca na web",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Toggle("Deixar o codec pesquisar quando precisar", webSearch) {
            UiSfx.play(Sfx.UI_SELECT)
            webSearch = it
        }
        Text(
            "Quem decide e o modelo: ele pesquisa quando a resposta depende de " +
                "algo atual (cotacao, noticia, versao) e responde de cabeca no " +
                "resto. Sem a chave abaixo a ferramenta nem e oferecida, entao " +
                "ele nao tenta e nao diz que falhou.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        Field("Chave da Brave Search", braveKey, secret = true) { braveKey = it }
        Text(
            "brave.com/search/api — plano gratuito, 2000 buscas por mes, " +
                "cadastro com e-mail comum. Pede cartao no cadastro mas nao " +
                "cobra no plano gratuito.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )

        Divider()

        Text(
            "Memoria",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Toggle("Lembrar coisas entre conversas", memoriaAtiva) {
            UiSfx.play(Sfx.UI_SELECT)
            memoriaAtiva = it
        }
        Text(
            "Ao fim de cada chamada, o modelo separa fatos duraveis sobre voce " +
                "e guarda no aparelho. Custa uma chamada extra ao provedor, " +
                "feita depois da resposta para nao aumentar a espera. Revise e " +
                "apague em MEMORIA, no HUD.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )

        Divider()

        Text(
            "Palavra de ativacao",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Text(
            "Liste aqui, separadas por virgula, as grafias que valem como " +
                "ativacao. Prefira palavras que existam no portugues e tenham " +
                "tres ou mais silabas: o reconhecedor so consegue escrever o que " +
                "conhece. O caminho mais confiavel e CALIBRAR ATIV., que aprende " +
                "com a sua voz em vez de adivinhar.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        OutlinedTextField(
            value = wakePhrases,
            onValueChange = { wakePhrases = it },
            modifier = Modifier.fillMaxWidth().height(110.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Phosphor
            )
        )
        TextButton(onClick = {
            UiSfx.play(Sfx.UI_SELECT)
            wakePhrases = ""
        }) { Text("limpar", color = Amber, fontFamily = FontFamily.Monospace) }

        Divider()

        Text(
            "Musica no Spotify",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Toggle("Aceitar comandos de musica", musicEnabled) {
            UiSfx.play(Sfx.UI_SELECT)
            musicEnabled = it
        }
        Text(
            "Diga \"toca pink floyd\", \"toca a playlist foco\", \"pausa\", " +
                "\"proxima\" ou \"anterior\". O comando de tocar so vale com o " +
                "verbo no comeco da frase, entao \"quem toca baixo no pink " +
                "floyd\" continua sendo pergunta.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )

        if (musicEnabled) {
            Text(
                "Atalhos: um por linha, apelido = endereco",
                color = PhosphorDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Text(
                "No Spotify, toque nos tres pontos da playlist, Compartilhar, " +
                    "Copiar link, e cole aqui. Sem atalho, a busca escolhe o " +
                    "resultado mais popular; com atalho, abre exatamente a SUA " +
                    "playlist.\n\nfoco = https://open.spotify.com/playlist/...\n" +
                    "treino = spotify:playlist:37i9dQZF1DX76Wlfdnj7AP",
                color = PhosphorDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            OutlinedTextField(
                value = musicShortcuts,
                onValueChange = { musicShortcuts = it },
                modifier = Modifier.fillMaxWidth().height(130.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Phosphor
                )
            )
        }

        Divider()

        Text(
            "Perfil de audio do fone",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AudioMode.entries.forEach { m ->
                val selected = m == audioMode
                OutlinedButton(
                    onClick = { UiSfx.play(Sfx.UI_SELECT); audioMode = m },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) Phosphor else Ink,
                        contentColor = if (selected) Ink else Phosphor
                    )
                ) { Text(m.label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
        Text(
            audioMode.hint,
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Text(
            "Se o fone travar, cortar o inicio das falas ou o microfone nao " +
                "pegar nada, troque para Midia. O som melhora, mas quem escuta " +
                "passa a ser o microfone do celular.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )

        Divider()

        Text(
            "Filtro de radio: ${(radioMix * 100).toInt()}%",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Slider(value = radioMix, onValueChange = { radioMix = it }, valueRange = 0f..1f)

        Divider()

        Text(
            "Volume dos efeitos: ${(sfxVolume * 100).toInt()}%",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Slider(value = sfxVolume, onValueChange = { sfxVolume = it }, valueRange = 0f..1f)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(Sfx.CALL, Sfx.OPEN, Sfx.OVER, Sfx.FAIL).forEach { s ->
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val player = PcmPlayer(22050, previewUsage(audioMode))
                            try {
                                SfxBank.render(ctx, s, sfxFull, sfxVolume)
                                    ?.let { player.playAndWait(it) }
                            } finally {
                                runCatching { player.finish() }
                            }
                        }
                    }
                }) {
                    Text(
                        s.name.lowercase(),
                        color = Phosphor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Text(
            "Silencio que encerra sua fala: ${endSilence.toInt()} ms",
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Text(
            "Menor responde antes; se ele cortar voce no meio da frase, aumente.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        Slider(
            value = endSilence,
            onValueChange = { endSilence = it },
            valueRange = 500f..2000f
        )

        Toggle("Pular o toque de chamada (economiza ~1,2 s por pergunta)", sfxMinimal) {
            UiSfx.play(Sfx.UI_SELECT)
            sfxMinimal = it
        }
        Toggle("Tocar os efeitos inteiros (mais imersivo, mais lento)", sfxFull) {
            UiSfx.play(Sfx.UI_SELECT)
            sfxFull = it
        }
        Toggle("Aceitar perguntas de seguimento sem repetir a palavra", followUp) { followUp = it }

        Toggle("Dizer a palavra de ativacao durante a resposta corta a fala", bargeIn) {
            UiSfx.play(Sfx.UI_SELECT)
            bargeIn = it
        }
        Text(
            "Se o aparelho estiver no alto-falante, sem fone, ele pode ouvir a " +
                "propria voz e se interromper sozinho. Nesse caso, desligue.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )

        Text(
            "Frases que desligam a chamada",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        Text(
            "Precisam ser a fala inteira, nao aparecer no meio dela. Assim " +
                "\"me explica como desliga um processo\" continua sendo pergunta.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        OutlinedTextField(
            value = hangUp,
            onValueChange = { hangUp = it },
            modifier = Modifier.fillMaxWidth().height(100.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Phosphor
            )
        )
        Toggle("Religar a escuta ao ligar o aparelho", autoStart) { autoStart = it }

        Divider()

        Text("Instrucoes do assistente", color = PhosphorDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            modifier = Modifier.fillMaxWidth().height(220.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Phosphor
            )
        )

        if (status.isNotBlank()) {
            Text(status, color = Amber, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = { save(); UiSfx.play(Sfx.UI_CONFIRM); status = "salvo"; onBack() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Phosphor, contentColor = Ink)
        ) { Text("SALVAR E VOLTAR", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("VOLTAR SEM SALVAR", color = Phosphor, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** As previas saem pela mesma rota que a chamada vai usar. */
private fun previewUsage(mode: AudioMode): Int = when (mode) {
    AudioMode.CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
    else -> AudioAttributes.USAGE_MEDIA
}

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit
) {
    Column {
        Text(label, color = PhosphorDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = Phosphor
            )
        )
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(0.8f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Divider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PhosphorDim)
    )
}
