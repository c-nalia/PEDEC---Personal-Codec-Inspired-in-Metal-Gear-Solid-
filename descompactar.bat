@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

rem ===========================================================================
rem  Descompacta um .tar.bz2 no Windows.
rem
rem  Use arrastando o arquivo para cima deste .bat, ou:
rem    descompactar.bat caminho\do\arquivo.tar.bz2 [pasta-de-destino]
rem
rem  Existe porque o tar do Windows e o bsdtar, que abre gzip e zip sozinho mas
rem  terceiriza bzip2 para um programa externo inexistente no Windows:
rem
rem    tar: Error opening archive: Can't initialize filter; unable to run
rem    program "bzip2 -d"
rem
rem  Isso atrapalha as duas partes do sherpa-onnx que voce baixa a mao:
rem  as bibliotecas (sherpa-onnx-*-android.tar.bz2) e as vozes.
rem ===========================================================================

if "%~1"=="" (
    echo.
    echo   Uso: arraste um arquivo .tar.bz2 para cima deste .bat
    echo   ou:  descompactar.bat arquivo.tar.bz2 [destino]
    echo.
    pause
    exit /b 1
)

set PACOTE=%~f1
set DESTINO=%~2
if "%DESTINO%"=="" set DESTINO=%~dp1

if not exist "%PACOTE%" (
    echo   ERRO: nao achei %PACOTE%
    pause
    exit /b 1
)

echo.
echo   Arquivo: %PACOTE%
echo   Destino: %DESTINO%
echo.

set EXTRAIU=0

rem [1] python: tarfile da biblioteca padrao le bz2 sem nada instalado a mais.
for %%P in (python py python3) do (
    if "!EXTRAIU!"=="0" (
        %%P -c "import tarfile,sys; tarfile.open(sys.argv[1],'r:bz2').extractall(sys.argv[2])" "%PACOTE%" "%DESTINO%" >nul 2>&1
        if not errorlevel 1 (
            set EXTRAIU=1
            echo   descompactado via python
        )
    )
)

rem [2] 7-Zip, em duas passadas: .tar.bz2 e um tar dentro de um bz2.
if "%EXTRAIU%"=="0" (
    for %%Z in ("%ProgramFiles%\7-Zip\7z.exe" "%ProgramFiles(x86)%\7-Zip\7z.exe") do (
        if "!EXTRAIU!"=="0" if exist %%Z (
            %%Z x -y -o"%DESTINO%" "%PACOTE%" >nul 2>&1
            if not errorlevel 1 (
                for %%T in ("%DESTINO%\*.tar") do %%Z x -y -o"%DESTINO%" "%%T" >nul 2>&1
                set EXTRAIU=1
                echo   descompactado via 7-Zip
            )
        )
    )
)

rem [3] bzip2 que vem escondido no Git for Windows. Basta estar no PATH para o
rem     bsdtar conseguir usar.
if "%EXTRAIU%"=="0" (
    for %%G in ("%ProgramFiles%\Git\usr\bin" "%ProgramFiles(x86)%\Git\usr\bin" "%LocalAppData%\Programs\Git\usr\bin") do (
        if "!EXTRAIU!"=="0" if exist "%%~G\bzip2.exe" (
            set "PATH=%%~G;!PATH!"
            tar -xf "%PACOTE%" -C "%DESTINO%" >nul 2>&1
            if not errorlevel 1 (
                set EXTRAIU=1
                echo   descompactado via bzip2 do Git
            )
        )
    )
)

rem [4] tar puro, se houver um bzip2 no PATH por outro motivo.
if "%EXTRAIU%"=="0" (
    tar -xf "%PACOTE%" -C "%DESTINO%" >nul 2>&1
    if not errorlevel 1 (
        set EXTRAIU=1
        echo   descompactado via tar
    )
)

echo.
if "%EXTRAIU%"=="0" (
    echo  ---------------------------------------------------------------------
    echo   Nao consegui. Nao achei python, nem 7-Zip, nem bzip2.
    echo.
    echo   Instale o 7-Zip e rode de novo:  https://www.7-zip.org
    echo   O arquivo continua intacto, nao precisa baixar de novo.
    echo  ---------------------------------------------------------------------
) else (
    echo   Pronto.
)
echo.
pause
