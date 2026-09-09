// ===========================================================================
//  ESTE ARQUIVO VAI NA RAIZ DO PROJETO:   C:\pedec\build.gradle.kts
//
//  Nao confunda com C:\pedec\app\build.gradle.kts, que e outro arquivo.
//  Este aqui so declara as VERSOES dos plugins. O do modulo app aplica esses
//  plugins sem versao e configura o Android de verdade.
// ===========================================================================

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
