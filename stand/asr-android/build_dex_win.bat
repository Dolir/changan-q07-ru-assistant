@echo off
REM build_dex_win.bat - Windows port of build_dex.sh.
REM Compiles com/stand/** (RuBridge + Ru2Zh + GigaAsr + PiperCaTts + TeraTts) and the sherpa-onnx
REM java API into build\dex7\classes.dex. build_q07.sh installs that dex as classes35.dex.
REM
REM Requires: javac (JDK 17+), Android SDK build-tools 34.0.0 + platforms\android-34\android.jar.
REM   set ANDROID_HOME if the SDK is not in %LOCALAPPDATA%\Android\Sdk
setlocal enabledelayedexpansion
set "D=%~dp0"
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "AJAR=%ANDROID_HOME%\platforms\android-34\android.jar"
set "BT=%ANDROID_HOME%\build-tools\34.0.0"
if not exist "%AJAR%" (echo [x] android.jar not found: %AJAR% & exit /b 1)
if not exist "%BT%\d8.bat" (echo [x] d8 not found in %BT% & exit /b 1)

set "OUT=%D%build\dex7"
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
if exist "%D%build\stubs" rmdir /s /q "%D%build\stubs"
mkdir "%D%build\stubs"

REM -- 1. compile-only stubs of the app's own TTS interfaces: classpath only, never fed to d8 --
if exist "%D%src-stubs" (
  dir /s /b "%D%src-stubs\*.java" > "%D%build\stubs_src.txt"
  javac -nowarn -source 17 -target 17 -d "%D%build\stubs" -classpath "%AJAR%" @"%D%build\stubs_src.txt" || exit /b 1
)

REM -- 2. our sources + sherpa-onnx java api --
set "CP=%AJAR%"
if exist "%D%libs\ort-android-classes.jar" set "CP=%CP%;%D%libs\ort-android-classes.jar"
set "CP=%CP%;%D%build\stubs"
dir /s /b "%D%src\com\stand\*.java" > "%D%build\src.txt"
if exist "%D%sherpa-src" dir /s /b "%D%sherpa-src\*.java" >> "%D%build\src.txt"
javac -nowarn -source 17 -target 17 -encoding UTF-8 -d "%OUT%" -classpath "%CP%" @"%D%build\src.txt" || exit /b 1

REM -- 3. dex --
del /q "%OUT%\classes*.dex" 2>nul
jar cf "%D%build\classes.jar" -C "%OUT%" . || exit /b 1
set "ORT="
if exist "%D%libs\ort-android-classes.jar" set "ORT=%D%libs\ort-android-classes.jar"
call "%BT%\d8.bat" --min-api 29 --lib "%AJAR%" --output "%OUT%" "%D%build\classes.jar" "%ORT%" || exit /b 1

REM -- 4. loader: its own dex, never overlaid (build_q07.sh installs it as classes36.dex) --
set "LOUT=%D%build\dex_loader"
if exist "%LOUT%" rmdir /s /q "%LOUT%"
mkdir "%LOUT%"
dir /s /b "%D%loader\*.java" > "%D%build\loader_src.txt"
javac -nowarn -source 17 -target 17 -encoding UTF-8 -d "%LOUT%" -classpath "%AJAR%" @"%D%build\loader_src.txt" || exit /b 1
jar cf "%D%build\loader.jar" -C "%LOUT%" . || exit /b 1
call "%BT%\d8.bat" --min-api 29 --lib "%AJAR%" --output "%LOUT%" "%D%build\loader.jar" || exit /b 1

echo [build_dex_win] dex written to %OUT%:
dir /b "%OUT%\classes*.dex"
for %%F in ("%OUT%\classes.dex") do echo   classes.dex: %%~zF bytes
