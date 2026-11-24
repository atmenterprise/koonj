@echo off
REM ============================================================================
REM Koonj Server — Service Management Script (Windows)
REM Usage: koonj-server.cmd start|stop|restart|status
REM ============================================================================
setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%.."
set "KOONJ_HOME=%CD%"
popd

set "ENV_FILE=%KOONJ_HOME%\config\koonj.env"
if exist "%ENV_FILE%" (
  for /f "usebackq tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    set "LINE=%%a"
    if not "!LINE:~0,1!"=="#" if not "%%a"=="" set "%%a=%%~b"
  )
)

set "KOONJ_JAR=%KOONJ_HOME%\lib\koonj-server.jar"
set "KOONJ_LOG_DIR=%KOONJ_HOME%\logs"
set "KOONJ_PID_FILE=%KOONJ_LOG_DIR%\koonj-server.pid"
set "KOONJ_CONSOLE_LOG=%KOONJ_LOG_DIR%\koonj-server-console.log"
if not defined JAVA_OPTS set "JAVA_OPTS=-Xms512m -Xmx2g -XX:+UseG1GC"
if not defined SPRING_PROFILES_ACTIVE set "SPRING_PROFILES_ACTIVE=production"
if not defined KOONJ_SERVER_PORT set "KOONJ_SERVER_PORT=8080"
set "SPRING_CONFIG=%KOONJ_HOME%\config\application-production.yml"
set "SPRING_CONFIG_FLAG="
if exist "%SPRING_CONFIG%" set "SPRING_CONFIG_FLAG=--spring.config.additional-location=file:%SPRING_CONFIG%"

if not exist "%KOONJ_LOG_DIR%" mkdir "%KOONJ_LOG_DIR%"

if /I "%~1"=="start"   goto :do_start
if /I "%~1"=="stop"    goto :do_stop
if /I "%~1"=="restart" goto :do_restart
if /I "%~1"=="status"  goto :do_status
echo Usage: %~nx0 {start^|stop^|restart^|status}
exit /b 1

:do_start
if not exist "%KOONJ_JAR%" ( echo ERROR: JAR not found at %KOONJ_JAR% & exit /b 1 )
if exist "%KOONJ_PID_FILE%" (
  set /p EXISTING_PID=<"%KOONJ_PID_FILE%"
  tasklist /FI "PID eq !EXISTING_PID!" 2>nul | findstr /I "java" >nul
  if !ERRORLEVEL! equ 0 ( echo Already running ^(PID !EXISTING_PID!^). & exit /b 0 )
  del "%KOONJ_PID_FILE%" 2>nul
)
echo Starting Koonj Server ...
start /B "" javaw %JAVA_OPTS% -Dspring.profiles.active=%SPRING_PROFILES_ACTIVE% -Dserver.port=%KOONJ_SERVER_PORT% -jar "%KOONJ_JAR%" %SPRING_CONFIG_FLAG% >> "%KOONJ_CONSOLE_LOG%" 2>&1
timeout /t 3 /nobreak >nul
for /f "tokens=2" %%p in ('tasklist /FI "IMAGENAME eq javaw.exe" /FO LIST 2^>nul ^| findstr "PID:"') do set "NEW_PID=%%p"
if defined NEW_PID ( echo !NEW_PID!>"%KOONJ_PID_FILE%" & echo   Started ^(PID !NEW_PID!^) )
exit /b 0

:do_stop
if not exist "%KOONJ_PID_FILE%" ( echo Not running. & exit /b 0 )
set /p STOP_PID=<"%KOONJ_PID_FILE%"
echo Stopping ^(PID %STOP_PID%^) ...
taskkill /PID %STOP_PID% /F >nul 2>&1
del "%KOONJ_PID_FILE%" 2>nul
echo   Stopped.
exit /b 0

:do_restart
call :do_stop
timeout /t 3 /nobreak >nul
goto :do_start

:do_status
if not exist "%KOONJ_PID_FILE%" ( echo Not running. & exit /b 1 )
set /p STATUS_PID=<"%KOONJ_PID_FILE%"
tasklist /FI "PID eq %STATUS_PID%" 2>nul | findstr /I "java" >nul
if %ERRORLEVEL% equ 0 ( echo Running ^(PID %STATUS_PID%^). ) else ( echo Not running. & del "%KOONJ_PID_FILE%" 2>nul & exit /b 1 )
exit /b 0
