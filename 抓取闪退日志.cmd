@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
set "ADB=adb"
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if exist "%~dp0.build\sdk\platform-tools\adb.exe" set "ADB=%~dp0.build\sdk\platform-tools\adb.exe"
if not exist "%~dp0.build" mkdir "%~dp0.build"
echo 手机需要开启 USB 调试，并允许这台电脑连接。
"%ADB%" devices
"%ADB%" logcat -d -b crash > "%~dp0.build\phone-crash.log" 2>&1
"%ADB%" logcat -d -v time AndroidRuntime:E *:S >> "%~dp0.build\phone-crash.log" 2>&1
echo.
echo 日志已保存到 .build\phone-crash.log。
pause
endlocal