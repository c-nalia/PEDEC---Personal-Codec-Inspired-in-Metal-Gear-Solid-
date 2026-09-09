@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

rem ===========================================================================
rem  Baixa uma voz de saida do sherpa-onnx (TTS offline, em portugues).
rem
rem  O tar que vem no Windows e o bsdtar. Ele abre gzip e zip sozinho, mas para
rem  bzip2 ele CHAMA UM PROGRAMA EXTERNO chamado "bzip2" — que o Windows nao
rem  tem. O erro que aparece e:
rem
rem    tar: Error opening archive: Can't initialize filter; unable to run
rem    program "bzip2 -d"
rem
rem  E falta de descompactador, nao arquivo corrompido. Por isso a extracao
rem  aqui tenta quatro caminhos diferentes antes de desistir.
rem ===========================================================================

set DESTINO=%~dp0app\src\main\assets\sherpa-tts
set TEMP_DIR=%~dp0.tmp-voz
set BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models

echo.
echo   Qual voz?
echo.
echo     [1] Piper pt-BR "faber"   ~64 MB   melhor qualidade
echo     [2] MMS portugues         ~40 MB   mais simples, sem espeak
echo.
set /p ESCOLHA=  Digite 1 ou 2 (padrao 1):

if "%ESCOLHA%"=="2" (
    set NOME=vits-mms-por
) else (
    set NOME=vits-piper-pt_BR-faber-medium
)

set PACOTE=%TEMP_DIR%\%NOME%.tar.bz2

echo.
echo   Voz: %NOME%
echo.

if not exist "%TEMP_DIR%" mkdir "%TEMP_DIR%"

rem ------------------------------------------------------------------ download
rem Se o arquivo ja veio numa tentativa anterior, nao baixa de novo. Sao 64 MB;
rem repetir o download por causa de uma falha na descompactacao seria desperdicio.
set PULAR=0
if exist "%PACOTE%" for %%S in ("%PACOTE%") do if %%~zS GTR 1000000 set PULAR=1
if exist "%TEMP_DIR%\voz.tar.bz2" for %%S in ("%TEMP_DIR%\voz.tar.bz2") do (
    if %%~zS GTR 1000000 (
        move /y "%TEMP_DIR%\voz.tar.bz2" "%PACOTE%" >nul
        set PULAR=1
    )
)

if "%PULAR%"=="1" (
    echo   pacote ja baixado, reaproveitando
) else (
    echo   baixando...
    powershell -NoProfile -Command ^
        "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -Uri '%BASE%/%NOME%.tar.bz2' -OutFile '%PACOTE%' -UseBasicParsing -ErrorAction Stop; exit 0 } catch { exit 1 }"
    if errorlevel 1 (
        echo.
        echo  ERRO: o download falhou. Confira os nomes disponiveis em
        echo    https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
        pause
        exit /b 1
    )
)

rem ------------------------------------------------------------------ extracao
echo   descompactando...
set EXTRAIU=0

rem [1] python: o modulo tarfile da biblioteca padrao le bz2 direto, sem
rem     precisar de nada instalado alem do proprio python.
if "%EXTRAIU%"=="0" (
    for %%P in (python py python3) do (
        if "!EXTRAIU!"=="0" (
            %%P -c "import tarfile,sys; tarfile.open(sys.argv[1],'r:bz2').extractall(sys.argv[2])" "%PACOTE%" "%TEMP_DIR%" >nul 2>&1
            if not errorlevel 1 (
                set EXTRAIU=1
                echo     via python
            )
        )
    )
)

rem [2] 7-Zip: precisa de duas passadas, porque .tar.bz2 e um tar dentro de um
rem     bz2 e o 7z so tira uma camada por vez.
if "%EXTRAIU%"=="0" (
    for %%Z in ("%ProgramFiles%\7-Zip\7z.exe" "%ProgramFiles(x86)%\7-Zip\7z.exe") do (
        if "!EXTRAIU!"=="0" if exist %%Z (
            %%Z x -y -o"%TEMP_DIR%" "%PACOTE%" >nul 2>&1
            if not errorlevel 1 (
                %%Z x -y -o"%TEMP_DIR%" "%TEMP_DIR%\%NOME%.tar" >nul 2>&1
                if not errorlevel 1 (
                    set EXTRAIU=1
                    echo     via 7-Zip
                )
            )
        )
    )
)

rem [3] bzip2 do Git for Windows. O bsdtar so precisa achar "bzip2" no PATH;
rem     o Git instala um em usr\bin e quase ninguem sabe que ele esta ali.
if "%EXTRAIU%"=="0" (
    for %%G in ("%ProgramFiles%\Git\usr\bin" "%ProgramFiles(x86)%\Git\usr\bin" "%LocalAppData%\Programs\Git\usr\bin") do (
        if "!EXTRAIU!"=="0" if exist "%%~G\bzip2.exe" (
            set "PATH=%%~G;!PATH!"
            tar -xf "%PACOTE%" -C "%TEMP_DIR%" >nul 2>&1
            if not errorlevel 1 (
                set EXTRAIU=1
                echo     via bzip2 do Git
            )
        )
    )
)

rem [4] tar puro, caso exista um bzip2 no PATH por outro motivo.
if "%EXTRAIU%"=="0" (
    tar -xf "%PACOTE%" -C "%TEMP_DIR%" >nul 2>&1
    if not errorlevel 1 (
        set EXTRAIU=1
        echo     via tar
    )
)

if "%EXTRAIU%"=="0" (
    echo.
    echo  ---------------------------------------------------------------------
    echo   NAO CONSEGUI DESCOMPACTAR. O arquivo esta baixado e intacto em:
    echo     %PACOTE%
    echo.
    echo   O tar do Windows nao abre bz2 sozinho. Resolva de UM destes jeitos:
    echo.
    echo     - Instale o 7-Zip:  https://www.7-zip.org
    echo       Depois rode este script de novo. Ele reaproveita o download.
    echo.
    echo     - Ou abra o arquivo acima a mao com qualquer descompactador e
    echo       copie o CONTEUDO da pasta %NOME% para:
    echo         app\src\main\assets\sherpa-tts
    echo.
    echo   Vale o mesmo para o sherpa-onnx-*-android.tar.bz2 das bibliotecas.
    echo  ---------------------------------------------------------------------
    pause
    exit /b 1
)

rem ------------------------------------------------------------------ instalacao
rem O tarball cria uma pasta com o nome do modelo. O conteudo DELA e que vai
rem para assets/sherpa-tts, sem o nivel extra: SherpaTts.kt procura o .onnx
rem direto em sherpa-tts/, nao em sherpa-tts/<nome>/.
if exist "%DESTINO%" rmdir /s /q "%DESTINO%"
mkdir "%DESTINO%"

if exist "%TEMP_DIR%\%NOME%" (
    xcopy /e /i /q /y "%TEMP_DIR%\%NOME%\*" "%DESTINO%\" >nul
) else (
    echo   aviso: pasta %NOME% nao encontrada, copiando o que veio
    xcopy /e /i /q /y "%TEMP_DIR%\*.onnx" "%DESTINO%\" >nul 2>&1
    xcopy /e /i /q /y "%TEMP_DIR%\tokens.txt" "%DESTINO%\" >nul 2>&1
)

rem ------------------------------------------------------------------ conferencia
echo.
echo   Conferindo...
echo.

set TEM_ONNX=0
for %%A in ("%DESTINO%\*.onnx") do (
    set TEM_ONNX=1
    echo     ok  %%~nxA  ^(%%~zA bytes^)
)
if exist "%DESTINO%\tokens.txt" (
    echo     ok  tokens.txt
) else (
    echo     FALTA tokens.txt
    set TEM_ONNX=0
)
if exist "%DESTINO%\espeak-ng-data" echo     ok  espeak-ng-data ^(Piper^)

echo.
if "%TEM_ONNX%"=="0" (
    echo  ---------------------------------------------------------------------
    echo   Nao achei .onnx e tokens.txt em sherpa-tts. Abra a pasta
    echo   app\src\main\assets\sherpa-tts e deixe os arquivos no primeiro
    echo   nivel, sem pasta intermediaria.
    echo  ---------------------------------------------------------------------
    pause
    exit /b 1
)

rmdir /s /q "%TEMP_DIR%"

echo  ---------------------------------------------------------------------
echo   Voz instalada.
echo.
echo   No app: Configuracao -^> motor de voz -^> Sherpa
echo  ---------------------------------------------------------------------
echo.
pause
