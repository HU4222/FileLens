@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem Fixed full JDK: the Zulu JRE on PATH has no jlink.exe.
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not defined ANDROID_SDK_ROOT set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
set "JAVA_TOOL_OPTIONS=-Dsun.jnu.encoding=UTF-8"
set "ANDROID_USER_HOME=%CD%\.android-home"
set "ANDROID_SDK_HOME="
set "ANDROID_PREFS_ROOT="
set "GRADLE_USER_HOME=%CD%\.gradle-home"
if not exist "%CD%\.tmp" mkdir "%CD%\.tmp" >nul 2>&1
set "TEMP=%CD%\.tmp"
set "TMP=%CD%\.tmp"

call gradlew.bat assembleDebug --no-daemon
exit /b %ERRORLEVEL%
