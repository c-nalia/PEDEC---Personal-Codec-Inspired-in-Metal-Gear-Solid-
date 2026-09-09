// ===========================================================================
//  ESTE ARQUIVO VAI DENTRO DA PASTA APP:   C:\pedec\app\build.gradle.kts
//
//  Nao confunda com C:\pedec\build.gradle.kts, que fica na raiz.
//  Os plugins aqui aparecem SEM versao de proposito: a versao vem do arquivo
//  da raiz. Se voce colocar este arquivo na raiz, o build falha com
//  "Plugin [id: 'com.android.application'] was not found".
// ===========================================================================

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Assinatura lida de keystore.properties, na raiz do projeto. Esse arquivo
// contem a senha da sua chave e por isso fica fora do controle de versao
// (ja esta no .gitignore). Se ele nao existir, o build release simplesmente
// sai sem assinatura em vez de quebrar.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        // Properties.load(InputStream) le o arquivo como ISO-8859-1, por
        // especificacao da linguagem. Se a senha tiver acento ou cedilha e o
        // arquivo estiver salvo em UTF-8 (o padrao de qualquer editor hoje),
        // cada caractere acentuado vira dois caracteres errados: "abç123"
        // chega no Gradle como "abÃ§123". O erro resultante e
        // "keystore password was incorrect", sem nenhuma mencao a codificacao.
        //
        // Aqui decodificamos o texto antes e usamos load(Reader), que respeita
        // o charset. Tenta UTF-8 e cai para ISO-8859-1 se os bytes nao forem
        // UTF-8 valido, cobrindo arquivo salvo como ANSI tambem.
        val bytes = keystorePropsFile.readBytes()
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
        load(StringReader(text))
    }
}

// Em um arquivo .properties o espaco DEPOIS do valor e preservado. Uma senha
// colada com um espaco sobrando no fim vira outra senha, e o erro que aparece
// e "keystore password was incorrect" — sem nenhuma pista do espaco. O trim
// resolve isso de vez.
fun ksProp(name: String): String? =
    keystoreProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

val hasSigning = ksProp("storeFile") != null

android {
    namespace = "br.unesp.pedec"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.unesp.pedec"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // ------------------------------------------------------------------
        // So arm64. Isto sozinho tira ~122 MB do APK.
        //
        // O onnxruntime do sherpa e o libvosk sao compilados para toda ABI que
        // exista, e o Gradle empacota TODAS por padrao: x86 (25,9 MB), x86_64
        // (25,0 MB), armeabi-v7a (15,0 MB) e mais mips. Nenhuma dessas roda no
        // seu aparelho — x86 so serve para emulador e armeabi-v7a para celular
        // de 32 bits, coisa de antes de 2016.
        //
        // Se um dia precisar rodar no emulador do Android Studio, acrescente
        // "x86_64" na lista abaixo. Para celular antigo de 32 bits,
        // "armeabi-v7a".
        // ------------------------------------------------------------------
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    if (hasSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(ksProp("storeFile")!!)
                storePassword = ksProp("storePassword")
                keyAlias = ksProp("keyAlias")
                // keytool moderno cria keystore no formato PKCS12, onde a senha
                // da chave e obrigatoriamente igual a do keystore. Se voce nao
                // informar keyPassword, usamos a mesma — que e o certo aqui.
                keyPassword = ksProp("keyPassword") ?: ksProp("storePassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // O lint do AGP 8.5.2 embute a plataforma do IntelliJ para analisar o
    // codigo, e essa plataforma quebra sob o Gradle 9.x ao ser encerrada:
    //   Task :app:lintVitalAnalyzeRelease FAILED
    //   Already disposed: MessageBus(owner={}, disposeState=DISPOSED_STATE)
    //
    // O lint e analise estatica e nao participa da geracao do APK. Desligar
    // a checagem no build de release contorna o incidente sem afetar o que
    // e compilado. Se um dia voce subir o AGP para 8.7 ou mais novo com um
    // Gradle compativel, pode remover este bloco inteiro.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // O SoundPool le os efeitos por descritor de arquivo e nao aceita
    // asset comprimido dentro do APK.
    androidResources {
        noCompress += "wav"
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // material-icons-extended saiu daqui, e o ganho foi grande.
    //
    // Ele traz alguns milhares de icones vetoriais, cada um virando codigo. No
    // APK isso aparecia como 11.472 referencias a androidx.compose.material no
    // classes.dex — que estava com 32 MB. O app nao usa NENHUM icone: a
    // interface inteira e texto monoespacado, como um terminal de codec.
    //
    // Se um dia precisar de um icone, prefira desenhar em Canvas (como o painel
    // de frequencia ja faz) ou importar so o pacote base, material-icons-core.
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Voz offline: palavra de ativacao e transcricao, sem chave e sem conta
    implementation("com.alphacephei:vosk-android:0.3.47")

    // sherpa-onnx NAO aparece aqui, e isso e proposital.
    // Usado apenas para a VOZ DE SAIDA. A palavra de ativacao e do Vosk.
    //
    // O projeto nao publica AAR nem pacote Maven. Ele distribui as duas metades
    // separadas, e nenhuma das duas se declara como dependencia:
    //
    //   .so  ->  app/src/main/jniLibs/<abi>/   (o Gradle acha sozinho)
    //   .kt  ->  app/src/main/java/com/k2fsa/sherpa/onnx/  (vira codigo do app)
    //
    // Rode preparar-sherpa.bat na raiz para montar as duas.

    // HTTP (Claude + ElevenLabs)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Guarda as chaves de API criptografadas
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
