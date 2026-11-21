@echo off
REM ============================================================================
REM Koonj — On-Premise Installer (Windows)
REM Usage (run as Administrator):
REM   install.cmd                          — install to C:\koonj
REM   install.cmd --prefix D:\apps\koonj   — custom path
REM   install.cmd --uninstall              — remove
REM ============================================================================
setlocal EnableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
set "INSTALL_PREFIX=C:\koonj"
set "UNINSTALL=false"

:parse_args
if "%~1"=="" goto :end_parse
if /I "%~1"=="--prefix"    ( set "INSTALL_PREFIX=%~2" & shift & shift & goto :parse_args )
if /I "%~1"=="--uninstall" ( set "UNINSTALL=true" & shift & goto :parse_args )
if /I "%~1"=="--help" ( echo Usage: install.cmd [--prefix PATH] [--uninstall] & exit /b 0 )
echo Unknown: %~1 & exit /b 1
:end_parse

if "%UNINSTALL%"=="true" (
  echo Uninstalling Koonj ...
  where nssm >nul 2>&1 && ( nssm stop KoonjServer >nul 2>&1 & nssm remove KoonjServer confirm >nul 2>&1 )
  echo Done. %INSTALL_PREFIX% was NOT removed.
  exit /b 0
)

where java >nul 2>&1 || ( echo ERROR: Java 21+ required. & exit /b 1 )

echo ============================================================
echo   Koonj Installer -^> %INSTALL_PREFIX%
echo ============================================================

for %%d in (bin config lib web logs keycloak) do if not exist "%INSTALL_PREFIX%\%%d" mkdir "%INSTALL_PREFIX%\%%d"

if exist "%SCRIPT_DIR%lib\koonj-server.jar" copy /Y "%SCRIPT_DIR%lib\koonj-server.jar" "%INSTALL_PREFIX%\lib\" >nul
if exist "%SCRIPT_DIR%web\" xcopy /E /Q /Y "%SCRIPT_DIR%web\*" "%INSTALL_PREFIX%\web\" >nul
copy /Y "%SCRIPT_DIR%bin\koonj-server.cmd" "%INSTALL_PREFIX%\bin\" >nul
copy /Y "%SCRIPT_DIR%bin\koonj-server.sh"  "%INSTALL_PREFIX%\bin\" >nul 2>&1

if not exist "%INSTALL_PREFIX%\config\koonj.env" (
  copy /Y "%SCRIPT_DIR%config\koonj.env" "%INSTALL_PREFIX%\config\" >nul
) else ( echo   * koonj.env exists - skipped )

if not exist "%INSTALL_PREFIX%\config\application-production.yml" (
  copy /Y "%SCRIPT_DIR%config\application-production.yml" "%INSTALL_PREFIX%\config\" >nul
) else ( echo   * application-production.yml exists - skipped )

xcopy /E /Q /Y "%SCRIPT_DIR%keycloak\*" "%INSTALL_PREFIX%\keycloak\" >nul 2>&1

echo.
echo ============================================================
echo   Installation complete!
echo ============================================================
echo   Next: edit %INSTALL_PREFIX%\config\koonj.env
echo   Then: %INSTALL_PREFIX%\bin\koonj-server.cmd start
echo.

where nssm >nul 2>&1 && (
  echo   TIP: Install as Windows service with NSSM:
  echo     nssm install KoonjServer java -jar "%INSTALL_PREFIX%\lib\koonj-server.jar"
  echo     nssm set KoonjServer AppDirectory "%INSTALL_PREFIX%"
  echo     nssm start KoonjServer
  echo.
)
exit /b 0
