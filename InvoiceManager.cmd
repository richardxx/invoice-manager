@echo off
setlocal
set "APP_HOME=%~dp0"
if exist "%APP_HOME%Run-InvoiceManage.ps1" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%APP_HOME%Run-InvoiceManage.ps1" %*
  exit /b %ERRORLEVEL%
)
set "JAR=%APP_HOME%invoice-manager-1.5.12.jar"
if not exist "%JAR%" set "JAR=%APP_HOME%target\invoice-manager-1.5.12.jar"
if not exist "%JAR%" (
  echo InvoiceManage JAR not found. Build the project with Maven first.
  pause
  exit /b 1
)
set "JAVA_EXE="
for /d %%J in ("D:\Program Files\Java\jdk-25*") do if not defined JAVA_EXE if exist "%%~fJ\bin\javaw.exe" set "JAVA_EXE=%%~fJ\bin\javaw.exe"
if not defined JAVA_EXE (
  echo JDK 25 not found under D:\Program Files\Java.
  pause
  exit /b 1
)
start "" /D "%APP_HOME%" "%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -jar "%JAR%" %*
