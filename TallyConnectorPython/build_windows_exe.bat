@echo off
setlocal

cd /d "%~dp0"

where py >nul 2>&1
if errorlevel 1 (
  echo Python launcher ^(py^) not found. Install Python 3.10+ on Windows and try again.
  exit /b 1
)

set "PYTHON_SELECTOR="
for %%V in (3.12 3.11 3.10 3.13) do (
  py -%%V --version >nul 2>&1
  if not errorlevel 1 (
    set "PYTHON_SELECTOR=-%%V"
    goto :python_found
  )
)

echo No supported Python runtime was found via py launcher. Install Python 3.10+ and try again.
exit /b 1

:python_found
echo Using Python version selected by py %PYTHON_SELECTOR%

if not exist ".venv-build" (
  py %PYTHON_SELECTOR% -m venv .venv-build
  if errorlevel 1 exit /b 1
)

call ".venv-build\Scripts\activate.bat"
if errorlevel 1 exit /b 1

python -m pip install --upgrade pip
if errorlevel 1 exit /b 1

pip install -r requirements.txt -r requirements-build.txt
if errorlevel 1 exit /b 1

if exist build rmdir /s /q build
if exist dist rmdir /s /q dist

pyinstaller ^
  --noconfirm ^
  --clean ^
  --onefile ^
  --name TallyConnectorPython ^
  --paths src ^
  --collect-all uvicorn ^
  --collect-all anyio ^
  --collect-all starlette ^
  --collect-all websockets ^
  --collect-all httptools ^
  --collect-all watchfiles ^
  --collect-all pydantic ^
  --collect-all pydantic_core ^
  --collect-all pydantic_settings ^
  --collect-all backports ^
  --hidden-import backports ^
  --hidden-import backports.tarfile ^
  --hidden-import xmltodict ^
  run_connector.py

if errorlevel 1 exit /b 1

if exist ".env" (
  copy /y ".env" "dist\.env" >nul
)
if exist ".env.windows-stability.example" (
  copy /y ".env.windows-stability.example" "dist\.env.example" >nul
) else (
  copy /y ".env.example" "dist\.env.example" >nul
)
copy /y "README.md" "dist\README.md" >nul

(
  echo @echo off
  echo cd /d %%~dp0
  echo set "RUNTIME_DIR=%%APPDATA%%\TallyConnectorPython"
  echo if not exist "%%RUNTIME_DIR%%" mkdir "%%RUNTIME_DIR%%"
  echo if exist "%%~dp0.env" copy /Y "%%~dp0.env" "%%RUNTIME_DIR%%\.env" ^>nul
  echo TallyConnectorPython.exe
) > "dist\start_connector.bat"

(
  echo Set WshShell = CreateObject("WScript.Shell"^)
  echo WshShell.Run Chr(34^) ^& WScript.Arguments(0^) ^& Chr(34^), 0, False
) > "dist\launch_hidden.vbs"

echo.
echo Build complete.
echo Output folder: %CD%\dist
if exist "dist\.env" (
  echo Packaged runtime defaults were copied from the project .env file.
) else (
  echo No project .env file was found, so the packaged app will bootstrap from .env.example/defaults.
)
echo The packaged app can auto-create its runtime config on first launch.
echo Sample config is available as dist\.env.example.
echo Run start_connector.bat so the runtime %%APPDATA%% config stays in sync.
