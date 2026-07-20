@echo off
cd /d "%~dp0"
call gradlew.bat --no-daemon :tools:shop-maker:run
pause
