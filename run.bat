@echo off
rem Build and run the Huffman compressor.  Usage: run.bat [port]
cd /d "%~dp0"
if not exist out mkdir out
javac -d out src\huffman\*.java || exit /b 1
set PORT=%1
if "%PORT%"=="" set PORT=8000
java -cp out huffman.Server %PORT%
