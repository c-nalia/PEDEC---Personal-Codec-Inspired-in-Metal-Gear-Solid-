@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

rem ===========================================================================
rem  Baixa a API Kotlin do sherpa-onnx (voz de saida) para dentro do projeto.
rem
rem  O sherpa-onnx nao publica AAR nem pacote Maven. Ele distribui duas metades
rem  separadas, e as duas precisam ser da MESMA versao:
rem
rem    1. as bibliotecas nativas (.so) -> voce baixa o .tar.bz2 a mao
rem    2. a API Kotlin (.kt)           -> este script baixa
rem
rem  Se as duas versoes divergirem, o app compila normalmente e quebra so na
rem  hora de carregar o modelo, com UnsatisfiedLinkError ou NoSuchFieldError.
rem  E por isso que a versao aqui e a mesma que voce vai digitar: para as duas
rem  metades nunca sairem de sincronia.
rem ===========================================================================

set VERSAO=v1.13.5
if not "%~1"=="" set VERSAO=%~1

set DESTINO=%~dp0app\src\main\java\com\k2fsa\sherpa\onnx
set BASE=https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/%VERSAO%/sherpa-onnx/kotlin-api

echo.
echo  Versao: %VERSAO%
echo  Destino: %DESTINO%
echo.

if not exist "%DESTINO%" mkdir "%DESTINO%"

rem So o Tts.kt e necessario: ele e autossuficiente, nao importa nenhuma
rem outra classe do sherpa. A palavra de ativacao voltou a ser do Vosk, entao
rem KeywordSpotter, OnlineRecognizer e companhia sairam do projeto.
set ARQUIVOS=Tts.kt

set BAIXADOS=0
for %%A in (%ARQUIVOS%) do (
    echo   baixando %%A ...
    powershell -NoProfile -Command ^
        "try { Invoke-WebRequest -Uri '%BASE%/%%A' -OutFile '%DESTINO%\%%A' -UseBasicParsing -ErrorAction Stop; exit 0 } catch { exit 1 }"
    if errorlevel 1 (
        echo      nao existe nesta versao, ignorado
    ) else (
        set /a BAIXADOS+=1
    )
)

echo.
if %BAIXADOS% LSS 1 (
    echo  ERRO: so %BAIXADOS% arquivos vieram. Confira se a versao %VERSAO%
    echo  existe em https://github.com/k2-fsa/sherpa-onnx/releases
    echo.
    pause
    exit /b 1
)

echo  OK: %BAIXADOS% arquivos em app\src\main\java\com\k2fsa\sherpa\onnx
echo.
echo  ---------------------------------------------------------------------
echo   FALTA A OUTRA METADE, e ela e manual:
echo.
echo   1. Baixe  sherpa-onnx-%VERSAO:~1%-android.tar.bz2
echo      (o do MEIO da lista: sem "rknn" e sem "static-link" no nome)
echo      https://github.com/k2-fsa/sherpa-onnx/releases
echo.
echo   2. Descompacte. Vai aparecer uma pasta  jniLibs  com subpastas
echo      arm64-v8a, armeabi-v7a, x86, x86_64.
echo.
echo   3. Copie a pasta jniLibs inteira para:
echo        %~dp0app\src\main\jniLibs
echo.
echo      O resultado tem que ficar assim:
echo        app\src\main\jniLibs\arm64-v8a\libsherpa-onnx-jni.so
echo.
echo      O Gradle acha essas bibliotecas sozinho. Nao precisa mexer em
echo      build.gradle.kts.
echo   ---------------------------------------------------------------------
echo.
echo   Depois disso falta o modelo: veja app\src\main\assets\SHERPA-ONNX.txt
echo.
pause
