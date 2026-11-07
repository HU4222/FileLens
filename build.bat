@echo off
rem ============================================================
rem SpaceLens 一键构建
rem   build.bat            出 release 包（需要 keystore.properties）
rem   build.bat debug      出 debug 包
rem   build.bat install    出 debug 包并安装到 adb 设备
rem   build.bat clean      清理
rem 所有路径都可用环境变量覆盖：
rem   SPACELENS_JDK / SPACELENS_SDK / SPACELENS_GRADLE / SPACELENS_ADB
rem ============================================================
setlocal EnableExtensions
chcp 936 >nul
set "HERE=%~dp0"
set "PROJ=%~dp0.."

if not defined SPACELENS_JDK set "SPACELENS_JDK=C:\Program Files\Java\jdk-17"
if not defined SPACELENS_SDK (
  if exist "C:\Users\ZHUANZ~1\AppData\Local\Android\Sdk\platforms\android-34\android.jar" (
    set "SPACELENS_SDK=C:\Users\ZHUANZ~1\AppData\Local\Android\Sdk"
  ) else (
    set "SPACELENS_SDK=C:\Users\ZhuanZ（无密码）\AppData\Local\Android\Sdk"
  )
)
if not defined SPACELENS_ADB set "SPACELENS_ADB=C:\Users\ZhuanZ（无密码）\Desktop\手机\platform-tools\adb.exe"

if not exist "%SPACELENS_JDK%\bin\java.exe" (
  echo [错误] JDK 17 不存在：%SPACELENS_JDK%
  exit /b 1
)
if not exist "%SPACELENS_SDK%\platforms\android-34\android.jar" (
  echo [错误] Android SDK 34 不存在：%SPACELENS_SDK%
  exit /b 1
)

set "JAVA_HOME=%SPACELENS_JDK%"
set "ANDROID_SDK_ROOT=%SPACELENS_SDK%"
set "ANDROID_HOME=%SPACELENS_SDK%"
rem 关键：项目路径含中文，Kotlin/Gradle 在 GBK 下会把绝对路径转义坏。
rem       Java 17 用 sun.jnu.encoding=UTF-8 读参数；Gradle 用户目录/临时目录放工程内。
set "JAVA_TOOL_OPTIONS=-Dsun.jnu.encoding=UTF-8"
set "ANDROID_USER_HOME=%HERE%.android-home"
set "ANDROID_SDK_HOME="
set "ANDROID_PREFS_ROOT="
set "GRADLE_USER_HOME=%HERE%.gradle-home"
if not exist "%HERE%.tmp" mkdir "%HERE%.tmp" >nul 2>&1
set "TEMP=%HERE%.tmp"
set "TMP=%HERE%.tmp"

set "GRADLE="
if defined SPACELENS_GRADLE set "GRADLE=%SPACELENS_GRADLE%"
if not defined GRADLE if exist "%HERE%gradle-8.7\bin\gradle.bat" set "GRADLE=%HERE%gradle-8.7\bin\gradle.bat"
if not defined GRADLE if exist "C:\ProgramData\WorkBuddy\chromium-env\cu061j\WorkBuddy\HU4222\droplink_android\gradle-8.7\bin\gradle.bat" set "GRADLE=C:\ProgramData\WorkBuddy\chromium-env\cu061j\WorkBuddy\HU4222\droplink_android\gradle-8.7\bin\gradle.bat"
if not defined GRADLE if exist "%HERE%gradlew.bat" set "GRADLE=%HERE%gradlew.bat"
if not defined GRADLE (
  echo [错误] 找不到 Gradle。设置 SPACELENS_GRADLE 指向 gradle.bat，或把 gradle-8.7 放进工程。
  exit /b 1
)

set "MODE=%~1"
if "%MODE%"=="" set "MODE=release"
if /I "%MODE%"=="clean" (
  call "%GRADLE%" -p "%HERE%." clean
  exit /b %ERRORLEVEL%
)

if /I "%MODE%"=="debug" (
  set "TASK=assembleDebug"
  set "APKDIR=%HERE%app\build\outputs\apk\debug"
) else (
  set "TASK=assembleRelease"
  set "APKDIR=%HERE%app\build\outputs\apk\release"
)

echo [信息] Gradle：%GRADLE%
echo [信息] JDK：%JAVA_HOME%
echo [信息] SDK：%ANDROID_SDK_ROOT%
echo [信息] 任务：%TASK%
call "%GRADLE%" -p "%HERE%." %TASK%
if errorlevel 1 (
  echo [错误] 构建失败
  exit /b 1
)

set "APK="
if /I "%MODE%"=="debug" set "APK=%APKDIR%\app-debug.apk"
if /I "%MODE%"=="release" set "APK=%APKDIR%\app-release.apk"
if not exist "%APK%" (
  echo [错误] 构建成功但找不到 APK：%APK%
  exit /b 1
)

for %%F in ("%APK%") do set "SIZE=%%~zF"
echo [成功] APK：%APK%  ^(%SIZE% 字节^)

if /I "%MODE%"=="release" (
  if %SIZE% GTR 3670016 (
    echo [错误] release APK 超过 3.5MB 硬线：%SIZE% 字节
    exit /b 1
  )
  if not exist "%PROJ%\版本与发布" mkdir "%PROJ%\版本与发布" >nul 2>&1
  copy /y "%APK%" "%PROJ%\版本与发布\SpaceLens-1.0.0.apk" >nul
  echo [成功] 已复制到 版本与发布\SpaceLens-1.0.0.apk
)

if /I "%MODE%"=="install" (
  if not exist "%SPACELENS_ADB%" (
    echo [错误] 找不到 adb：%SPACELENS_ADB%
    exit /b 1
  )
  "%SPACELENS_ADB%" install -r "%APKDIR%\app-debug.apk"
  if errorlevel 1 exit /b 1
  "%SPACELENS_ADB%" shell am start -W -n com.spacelens.app.debug/com.spacelens.app.MainActivity
  exit /b %ERRORLEVEL%
)

exit /b 0
