# Personal Codec

Assistente pessoal por voz para Android, no formato do rádio CODEC de *Metal
Gear Solid*. Você fala a palavra de ativação, ouve o bipe de chamada, faz a
pergunta e a resposta volta pelo fone com filtro de rádio.

Feito para fone de condução óssea: a resposta é **ouvida, nunca lida**, e isso
molda quase toda decisão do projeto — do formato do prompt ao jeito como os
resultados de busca são cortados.

---

## O que ele faz

- **Ativação por voz offline**, com Vosk. Sem chave, sem conta, sem rede.
- **Transcrição** pelo Vosk ou pelo reconhecedor do Android.
- **Resposta** por Claude, Gemini, Groq, OpenRouter, Cerebras ou Ollama local.
- **Busca na web** quando o modelo julga necessário, via Brave Search.
- **Memória entre conversas**: fatos duráveis sobre você, guardados no aparelho.
- **Voz de saída** pelo TTS do Android, pelo sherpa-onnx offline ou ElevenLabs.
- **Controle do Spotify** por comando falado.
- **HUD** com mostrador de nível de microfone ao vivo.

---

## Atenção: o repositório não é o app inteiro

Para o repositório ficar em 420 KB em vez de 276 MB, e por questões de licença,
três categorias de arquivo **ficam de fora** e você precisa providenciar:

| O que falta | Por quê | Como obter |
|---|---|---|
| Modelo do Vosk (~50 MB) | Licença própria, tamanho | Passo 1 abaixo |
| Biblioteca e voz do sherpa | Licença própria, tamanho | `preparar-sherpa.bat` + `baixar-voz-sherpa.bat` |
| Efeitos sonoros do MGS | **Copyright da Konami** | Veja abaixo |

### Sobre os efeitos sonoros

Os sons originais do codec pertencem à Konami e não podem ser redistribuídos.
Eles não estão aqui.

**O app funciona sem eles.** `SfxBank.render` devolve `null` quando o arquivo
não existe, e o serviço cai nos sons sintetizados em `CodecSfx.kt` — bipes,
chiado e tons de chamada gerados por código, sem dono. É o que você vai ouvir
ao clonar.

Se quiser os originais e tiver o jogo, coloque os `.wav` mono em
`app/src/main/assets/sfx/` com os nomes que `Sfx.kt` espera.

---

## O que você precisa antes de compilar

### 1. Modelo de voz do Vosk (obrigatório)

Baixe `vosk-model-small-pt-0.3` em <https://alphacephei.com/vosk/models> e
descompacte em:

```
app/src/main/assets/vosk-model-pt/
```

O instalador aceita dois formatos de pasta. Se dentro houver `am/final.mdl` e
`conf/mfcc.conf`, é o layout novo. Se os arquivos estiverem soltos no primeiro
nível (`final.mdl`, `mfcc.conf`, `HCLr.fst`), é o antigo — **os dois
funcionam**, `VoskModel.detect` reconhece cada um.

### 2. Uma chave de modelo de linguagem

Qualquer uma serve; troque no app sem recompilar. As gratuitas:

- **Gemini** — <https://aistudio.google.com/apikey>, sem cartão
- **Groq** — <https://console.groq.com/keys>, o mais rápido
- **Cerebras** — <https://cloud.cerebras.ai>
- **Ollama** — no seu PC, sem chave nenhuma

### 3. Opcionais

- **Busca na web**: chave da Brave em <https://brave.com/search/api>
- **Voz offline**: `preparar-sherpa.bat`, depois `baixar-voz-sherpa.bat`
- **Voz natural**: chave do ElevenLabs

---

## Compilar

```
git clone <este repositorio>
cd pedec
```

Copie `keystore.properties.exemplo` para `keystore.properties` e preencha com a
sua chave de assinatura. Sem esse arquivo o build **não quebra** — só sai sem
assinatura, o que basta para instalar via `adb`.

Depois, no Android Studio, ou por linha de comando:

```
gradlew assembleRelease
```

No Windows, `gerar-apk.bat` faz isso localizando o Java do Android Studio
sozinho.

### Scripts auxiliares

| Script | O que faz |
|---|---|
| `gerar-apk.bat` | Compila o APK release |
| `preparar-sherpa.bat` | Baixa a API Kotlin do sherpa-onnx |
| `baixar-voz-sherpa.bat` | Baixa e instala a voz offline |
| `enxugar-assets.bat` | Remove 17 MB de dicionários de idioma não usados |
| `descompactar.bat` | Abre `.tar.bz2`, que o tar do Windows não abre sozinho |

---

## Arquitetura, em uma passada

```
VoiceEngine ──► CodecService ──► LlmClient ──► PcmPlayer
 (microfone)     (orquestra)     (provedor)    (alto-falante)
```

**`VoiceEngine`** é dono exclusivo do microfone e do reconhecedor. Só a thread
de áudio dele encosta no `Recognizer` do Vosk — quem está de fora envia
comandos para uma fila. Isso não é preciosismo: o `Recognizer` é um ponteiro
para um objeto C e uso concorrente derruba o processo com SIGSEGV, sem stack
trace de Kotlin.

**`CodecService`** é um serviço em primeiro plano com
`foregroundServiceType="microphone"`. No Android 14+ isso exige `RECORD_AUDIO`
concedido e não permite início em segundo plano — o serviço confere antes,
porque o sistema recusa lançando.

**`CodecBus`** é a ponte com a interface, um `StateFlow` atualizado por
compare-and-set. Duas threads escrevem nele simultaneamente.

**Filtro de rádio** (`RadioFilter`): passa-alta em 320 Hz, passa-baixa em
3 kHz, realce em 1,8 kHz, saturação suave e chiado. É o que dá o timbre de
transmissão.

---

## Detalhes que costumam morder

**A palavra de ativação é aproximada por design.** "Pedec" não existe no léxico
de nenhum modelo de português, então o reconhecedor devolve o que mais se
parece: "pedeque", "pe deque", "pedeck". Em vez de brigar com isso, o app aceita
uma lista de grafias e compara com tolerância de edição. Use **CALIBRAR ATIV.**
para descobrir como o reconhecedor escreve a *sua* pronúncia — ele mostra
exatamente o que entendeu, e aquilo vira gatilho.

**Prefira palavra que exista em português e tenha três sílabas ou mais.** O
reconhecedor só consegue escrever o que conhece.

**O `.tar.bz2` não abre com o tar do Windows.** O bsdtar terceiriza bzip2 para
um programa externo que o Windows não tem. O erro é `unable to run program
"bzip2 -d"` e significa falta de descompactador, não arquivo corrompido. Use
`descompactar.bat`.

**Senha de keystore com acento quebra.** O formato `.properties` é lido como
ISO-8859-1 e o erro que aparece é "keystore password was incorrect", sem
mencionar codificação. Use só ASCII.

**Cancelador de eco e supressor de ruído nascem desligados.** Em vários
aparelhos, sobretudo com o AudioSource de reconhecimento, o supressor trata fala
de baixa energia como ruído e apaga a palavra inteira. Se nenhum detector
funcionar, o problema costuma estar antes deles — a tela **TESTAR MICROFONE**
existe para responder isso.

---

## Privacidade

- Chaves de API ficam em `EncryptedSharedPreferences` (AES-256-GCM, chave no
  Keystore do aparelho), nunca no código.
- A memória fica em `filesDir/memoria.json`, só no aparelho. Revise e apague em
  **MEMORIA**, no HUD.
- Ativação e transcrição pelo Vosk são 100% offline. O áudio só sai do aparelho
  se você escolher o reconhecedor do Android ou o ElevenLabs.

---

## Licença

Código sob licença MIT — veja `LICENSE`.

A licença cobre **apenas o código deste repositório**. Não cobre:

- os modelos do Vosk e do sherpa-onnx, cada um com licença própria;
- efeitos sonoros de *Metal Gear Solid*, de propriedade da Konami, que **não
  estão** aqui e não devem ser adicionados a um fork público.

Projeto pessoal, sem vínculo com a Konami.
