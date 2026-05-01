@echo off
set "JAVA_HOME=D:\Software\Android_studio\jbr"
set "ANDROID_HOME=D:\Software\Android_studio\Sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Building LANSync App...
cd /d D:\Code\LANSync\App
gradlew.bat assembleDebug --no-daemon
echo.
echo Done. APK: D:\Code\LANSync\App\app\build\outputs\apk\debug\app-debug.apk
pause
