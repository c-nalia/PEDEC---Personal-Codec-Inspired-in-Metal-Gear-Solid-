@echo off
REM ---------------------------------------------------------------------------
REM  Gera o APK assinado do Pedec sem abrir o Android Studio.
REM  Basta dar dois cliques neste arquivo.
REM ---------------------------------------------------------------------------
setlocal

cd /d "%~dp0"

REM Usa o Java que vem dentro do Android Studio, para nao depender de JDK
REM instalado a parte nem de JAVA_HOME configurado.
if exist "C:\Program Files\Android\Android Studio\jbr" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
) else if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr" (
    set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
)

if not defined JAVA_HOME (
    echo.
    echo  Nao achei o Java do Android Studio.
    echo  Abra este arquivo e ajuste o caminho de JAVA_HOME na mao.
    echo.
    pause
    exit /b 1
)

echo  JAVA_HOME = %JAVA_HOME%
echo.

if not exist "gradlew.bat" (
    echo.
    echo  gradlew.bat nao existe ainda. Ele e criado pelo Android Studio
    echo  quando a sincronizacao do Gradle termina pelo menos uma vez.
    echo  Abra o projeto no Studio, espere a sincronizacao acabar, feche,
    echo  e rode este arquivo de novo.
    echo.
    pause
    exit /b 1
)

if not exist "keystore.properties" (
    echo.
    echo  AVISO: keystore.properties nao encontrado.
    echo  O APK vai sair SEM ASSINATURA e o celular nao vai instalar.
    echo  Copie keystore.properties.exemplo para keystore.properties
    echo  e preencha com os dados da sua chave.
    echo.
    pause
)

echo  Compilando... a primeira vez demora alguns minutos.
echo.
call gradlew.bat --no-daemon assembleRelease

if errorlevel 1 (
    echo.
    echo  ===============================================
    echo   O BUILD FALHOU. O erro esta logo acima.
    echo  ===============================================
    echo.
    pause
    exit /b 1
)

echo.
echo  ===============================================
echo   APK PRONTO
echo   %CD%\app\build\outputs\apk\release\app-release.apk
echo  ===============================================
echo.
explorer "%CD%\app\build\outputs\apk\release"
pause
