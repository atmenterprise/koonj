@echo off
setlocal
set "KOONJ_MAVEN_TEST_OPTION="
set "KOONJ_MAVEN_VERSION_OPTION="

:parse_args
if "%~1"=="" goto :build
if /I "%~1"=="--skip-tests" (
  set "KOONJ_MAVEN_TEST_OPTION=-DskipTests"
  shift
  goto :parse_args
)
if /I "%~1"=="--version" (
  if "%~2"=="" (
    echo Missing value for --version >&2
    exit /b 1
  )
  set "KOONJ_MAVEN_VERSION_OPTION=-Ddeployment.version=%~2"
  shift
  shift
  goto :parse_args
)
if /I "%~1"=="--target" (
  if /I not "%~2"=="all" (
    echo The deployment ZIP contains both targets. Use --target all. >&2
    exit /b 1
  )
  shift
  shift
  goto :parse_args
)
if /I "%~1"=="--help" goto :help
if /I "%~1"=="-h" goto :help
echo Unknown option: %~1 >&2
exit /b 1

:build
pushd "%~dp0..\.." || exit /b 1
call mvn -B -ntp -pl koonj-assembly -am %KOONJ_MAVEN_TEST_OPTION% %KOONJ_MAVEN_VERSION_OPTION% clean package
set "BUILD_EXIT=%ERRORLEVEL%"
popd
exit /b %BUILD_EXIT%

:help
echo Usage: build.cmd [--skip-tests] [--version VERSION]
exit /b 0
