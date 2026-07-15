@echo off
setlocal

cd /d "%~dp0"

call build_windows_exe.bat
if errorlevel 1 exit /b 1

set "ISCC_EXE=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
if not exist "%ISCC_EXE%" set "ISCC_EXE=%ProgramFiles%\Inno Setup 6\ISCC.exe"
if not exist "%ISCC_EXE%" set "ISCC_EXE=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"

if not exist "%ISCC_EXE%" (
  echo Inno Setup 6 compiler not found.
  echo Install Inno Setup from https://jrsoftware.org/isinfo.php and run this script again.
  exit /b 1
)

"%ISCC_EXE%" windows_installer.iss
if errorlevel 1 exit /b 1

echo.
echo Installer build complete.
echo Output folder: %CD%\installer-dist
