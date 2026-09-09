@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

rem ===========================================================================
rem  Tira do APK o que nunca vai ser usado.
rem
rem  O espeak-ng-data que vem junto com os modelos Piper traz dicionario de
rem  fonetizacao de TODOS os idiomas que o espeak suporta: 113 arquivos, 17 MB.
rem  Num assistente que fala portugues, 112 deles sao peso morto — o russo
rem  sozinho ocupa 8,5 MB.
rem
rem  Pior que o tamanho do APK: essa pasta e COPIADA para a memoria interna na
rem  primeira fala, entao os 17 MB viram 17 MB no APK mais 17 MB no aparelho.
rem
rem  Rode uma vez depois de instalar a voz, e de novo se trocar de modelo.
rem ===========================================================================

set ESPEAK=%~dp0app\src\main\assets\sherpa-tts\espeak-ng-data

if not exist "%ESPEAK%" (
    echo.
    echo   Nada a fazer: nao existe espeak-ng-data em assets.
    echo   Ou voce usa um modelo MMS, que nao precisa dele, ou a voz ainda
    echo   nao foi instalada. Veja app\src\main\assets\SHERPA-TTS.txt
    echo.
    pause
    exit /b 0
)

echo.
echo   Enxugando %ESPEAK%
echo.

rem Guarda pt (o idioma do app) e en (fallback para termo tecnico e sigla,
rem que aparecem o tempo todo numa conversa sobre computacao).
set MANTER=pt_dict en_dict

set REMOVIDOS=0
set LIBERADO=0

for %%F in ("%ESPEAK%\*_dict") do (
    set NOME=%%~nxF
    set GUARDAR=0
    for %%K in (%MANTER%) do if /i "!NOME!"=="%%K" set GUARDAR=1
    if "!GUARDAR!"=="0" (
        set /a LIBERADO+=%%~zF/1024
        del /q "%%F"
        set /a REMOVIDOS+=1
    ) else (
        echo     mantido: !NOME!
    )
)

echo.
echo   %REMOVIDOS% dicionarios removidos, cerca de !LIBERADO! KB liberados.
echo.
echo   Isso vale o dobro na pratica: sai do APK e sai da copia que o app faz
echo   na memoria interna do aparelho.
echo.
echo   ATENCAO: se depois de recompilar a voz sair errada ou muda, algum
echo   idioma removido era necessario. Rode baixar-voz-sherpa.bat de novo
echo   para restaurar a pasta inteira.
echo.
pause
