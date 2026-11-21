@echo off
REM ============================================================================
REM Koonj — Master Build ^& Package Script (Windows)
REM ============================================================================
REM Builds koonj-server (Maven) and koonj-ui (npm) and assembles a
REM distributable package for on-premise or Docker deployment.
REM
REM Usage:
REM   build.cmd                        — build everything
REM   build.cmd --target on-premise    — on-premise package only
REM   build.cmd --target docker        — docker package only
REM   build.cmd --skip-tests           — skip Maven tests
REM   build.cmd --version 1.0.0        — override version label
REM ============================================================================
setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%\..\.."
set "PROJECT_ROOT=%CD%"
popd

REM ── Defaults ───────────────────────────────────────────────────────────────
set "TARGET=all"
set "SKIP_TESTS=false"
set "VERSION="
set "DIST_DIR=%PROJECT_ROOT%\dist"

REM ── Parse arguments ────────────────────────────────────────────────────────
:parse_args
if "%~1"=="" goto :end_parse
if /I "%~1"=="--target"     ( set "TARGET=%~2"  & shift & shift & goto :parse_args )
if /I "%~1"=="--skip-tests" ( set "SKIP_TESTS=true" & shift & goto :parse_args )
if /I "%~1"=="--version"    ( set "VERSION=%~2" & shift & shift & goto :parse_args )
if /I "%~1"=="--help" goto :show_help
if /I "%~1"=="-h" goto :show_help
echo Unknown option: %~1
exit /b 1
:end_parse

REM ── Resolve version ────────────────────────────────────────────────────────
if "%VERSION%"=="" (
  for /f "usebackq tokens=*" %%i in (`mvn -f "%PROJECT_ROOT%\pom.xml" help:evaluate -Dexpression^=project.version -q -DforceStdout 2^>nul`) do set "VERSION=%%i"
)
if "%VERSION%"=="" set "VERSION=0.1.0-SNAPSHOT"

echo ============================================================
echo   Koonj Build — v%VERSION%
echo   Target: %TARGET%
echo ============================================================

REM ── Pre-flight checks ─────────────────────────────────────────────────────
where java >nul 2>&1 || ( echo ERROR: Java 21+ is required. & exit /b 1 )
where mvn  >nul 2>&1 || ( echo ERROR: Maven 3.9+ is required. & exit /b 1 )
where node >nul 2>&1 || ( echo ERROR: Node.js 20+ is required. & exit /b 1 )
where npm  >nul 2>&1 || ( echo ERROR: npm is required. & exit /b 1 )

REM ── Clean previous output ─────────────────────────────────────────────────
if exist "%DIST_DIR%" rd /s /q "%DIST_DIR%"
mkdir "%DIST_DIR%"

REM ── Step 1: Build koonj-server ─────────────────────────────────────────────
echo.
echo ^> Building koonj-server ...
set "MVN_OPTS=-B -ntp"
if "%SKIP_TESTS%"=="true" set "MVN_OPTS=%MVN_OPTS% -DskipTests"

pushd "%PROJECT_ROOT%"
call mvn %MVN_OPTS% -pl koonj-server -am clean package
if %ERRORLEVEL% neq 0 ( echo ERROR: Maven build failed. & exit /b 1 )
popd

set "SERVER_JAR="
for %%f in ("%PROJECT_ROOT%\koonj-server\target\koonj-server-*.jar") do (
  echo %%~nxf | findstr /i "original sources" >nul || set "SERVER_JAR=%%f"
)
if "%SERVER_JAR%"=="" ( echo ERROR: Server JAR not found. & exit /b 1 )
echo   √ Server JAR built successfully.

REM ── Step 2: Build koonj-ui ────────────────────────────────────────────────
echo.
echo ^> Building koonj-ui ...
pushd "%PROJECT_ROOT%\koonj-ui"
call npm ci --no-audit --no-fund
call npm run build
if %ERRORLEVEL% neq 0 ( echo ERROR: UI build failed. & exit /b 1 )
popd

set "UI_DIST=%PROJECT_ROOT%\koonj-ui\dist"
if not exist "%UI_DIST%" ( echo ERROR: UI dist not found. & exit /b 1 )
echo   √ UI built successfully.

REM ── Step 3: On-Premise assembly ────────────────────────────────────────────
if "%TARGET%"=="all"        goto :build_on_premise
if "%TARGET%"=="on-premise" goto :build_on_premise
goto :skip_on_premise

:build_on_premise
echo.
echo ^> Assembling on-premise distribution ...
set "OP_DIR=%DIST_DIR%\koonj-%VERSION%-on-premise"
mkdir "%OP_DIR%\bin"     2>nul
mkdir "%OP_DIR%\config"  2>nul
mkdir "%OP_DIR%\lib"     2>nul
mkdir "%OP_DIR%\web"     2>nul
mkdir "%OP_DIR%\systemd" 2>nul
mkdir "%OP_DIR%\nginx"   2>nul
mkdir "%OP_DIR%\keycloak" 2>nul

copy /Y "%SERVER_JAR%" "%OP_DIR%\lib\koonj-server.jar" >nul
xcopy /E /Q /Y "%UI_DIST%\*" "%OP_DIR%\web\" >nul

copy /Y "%SCRIPT_DIR%on-premise\bin\koonj-server.sh"      "%OP_DIR%\bin\"  >nul
copy /Y "%SCRIPT_DIR%on-premise\bin\koonj-server.cmd"     "%OP_DIR%\bin\"  >nul
copy /Y "%SCRIPT_DIR%on-premise\install.sh"               "%OP_DIR%\"      >nul
copy /Y "%SCRIPT_DIR%on-premise\install.cmd"              "%OP_DIR%\"      >nul
copy /Y "%SCRIPT_DIR%on-premise\config\koonj.env.example" "%OP_DIR%\config\koonj.env" >nul
copy /Y "%SCRIPT_DIR%on-premise\config\application-production.yml" "%OP_DIR%\config\" >nul
copy /Y "%SCRIPT_DIR%on-premise\systemd\koonj-server.service" "%OP_DIR%\systemd\" >nul
copy /Y "%SCRIPT_DIR%on-premise\nginx\koonj.conf"         "%OP_DIR%\nginx\" >nul
copy /Y "%PROJECT_ROOT%\deploy\keycloak\koonj-realm.json" "%OP_DIR%\keycloak\" >nul

if exist "%PROJECT_ROOT%\docs\DEPLOYMENT-GUIDE.md" (
  copy /Y "%PROJECT_ROOT%\docs\DEPLOYMENT-GUIDE.md" "%OP_DIR%\" >nul
)
echo   √ On-premise distribution assembled: dist\koonj-%VERSION%-on-premise\
:skip_on_premise

REM ── Step 4: Docker assembly ────────────────────────────────────────────────
if "%TARGET%"=="all"    goto :build_docker
if "%TARGET%"=="docker" goto :build_docker
goto :skip_docker

:build_docker
echo.
echo ^> Assembling Docker distribution ...
set "DK_DIR=%DIST_DIR%\koonj-%VERSION%-docker"
mkdir "%DK_DIR%\server"   2>nul
mkdir "%DK_DIR%\ui"       2>nul
mkdir "%DK_DIR%\nginx"    2>nul
mkdir "%DK_DIR%\keycloak" 2>nul
mkdir "%DK_DIR%\config"   2>nul

copy /Y "%SERVER_JAR%" "%DK_DIR%\server\koonj-server.jar" >nul
xcopy /E /Q /Y "%UI_DIST%\*" "%DK_DIR%\ui\" >nul

copy /Y "%SCRIPT_DIR%docker\Dockerfile.server"           "%DK_DIR%\server\"   >nul
copy /Y "%SCRIPT_DIR%docker\Dockerfile.ui"               "%DK_DIR%\ui\"       >nul
copy /Y "%SCRIPT_DIR%docker\docker-compose.yml"          "%DK_DIR%\"          >nul
copy /Y "%SCRIPT_DIR%docker\.env.example"                "%DK_DIR%\.env"      >nul
copy /Y "%SCRIPT_DIR%docker\nginx\default.conf.template" "%DK_DIR%\nginx\"    >nul
copy /Y "%PROJECT_ROOT%\deploy\keycloak\koonj-realm.json" "%DK_DIR%\keycloak\" >nul
copy /Y "%PROJECT_ROOT%\deploy\keycloak\init-db.sql"     "%DK_DIR%\keycloak\" >nul

if exist "%PROJECT_ROOT%\docs\DEPLOYMENT-GUIDE.md" (
  copy /Y "%PROJECT_ROOT%\docs\DEPLOYMENT-GUIDE.md" "%DK_DIR%\" >nul
)
echo   √ Docker distribution assembled: dist\koonj-%VERSION%-docker\
:skip_docker

REM ── Summary ────────────────────────────────────────────────────────────────
echo.
echo ============================================================
echo   Build complete!
echo   Output: %DIST_DIR%\
echo ============================================================
dir "%DIST_DIR%" /B
echo.
exit /b 0

:show_help
echo Usage: build.cmd [--target all^|on-premise^|docker] [--skip-tests] [--version X.Y.Z]
exit /b 0
