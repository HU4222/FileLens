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

rem The wrapper distribution is not cached on this machine; reuse the local
rem Gradle 8.7 that the existing Android projects already use.
set "LOCAL_GRADLE=C:\ProgramData\WorkBuddy\chromium-env\cu061j\WorkBuddy\HU4222\droplink_android\gradle-8.7\bin\gradle.bat"
if exist "%LOCAL_GRADLE%" (
  call "%LOCAL_GRADLE%" -p "%CD%" assembleDebug --no-daemon
) else (
  call gradlew.bat assembleDebug
)
exit /b %ERRORLEVEL%
