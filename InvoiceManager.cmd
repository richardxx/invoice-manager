@echo off
setlocal
set "APP_HOME=%~dp0"
if not exist "%APP_HOME%Run-InvoiceManage.ps1" (
  echo InvoiceManage launcher script is missing.
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%APP_HOME%Run-InvoiceManage.ps1" %*
exit /b %ERRORLEVEL%
