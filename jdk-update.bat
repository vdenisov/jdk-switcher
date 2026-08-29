@echo off
setlocal

call "%~dp0jdk-common.bat"
if errorlevel 1 exit /b 1

groovy "%~dp0jdk-update.groovy" %*
