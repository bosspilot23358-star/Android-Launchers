@echo off
rem PlayHack5: sborka APK bez Android Studio i Gradle. Nuzhen tolko JDK 17.
rem   build.bat            - sobrat APK
rem   build.bat setup      - tolko skachat Android SDK
rem   build.bat install    - ustanovit APK cherez adb (build.bat install 192.168.1.50)
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java ne naiden. Ustanovite JDK 17: https://adoptium.net  ^(Temurin 17, galochka "Add to PATH"^)
  pause
  exit /b 1
)
java Build.java %*
if errorlevel 1 (
  pause
  exit /b 1
)
pause
