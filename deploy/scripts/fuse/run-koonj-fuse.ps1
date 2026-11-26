$ErrorActionPreference = 'Stop'

foreach ($name in 'KOONJ_FUSE_BACKING_DIR', 'KOONJ_AGENT_CLIENT_SECRET') {
    if (-not [Environment]::GetEnvironmentVariable($name)) {
        throw "Set $name before starting Koonj FUSE."
    }
}

if (-not $env:KOONJ_FUSE_MOUNT_POINT) { $env:KOONJ_FUSE_MOUNT_POINT = 'K:\' }
$jar = $env:KOONJ_FUSE_JAR
if (-not $jar) {
    $jar = Join-Path $PSScriptRoot '..\lib\koonj-fuse.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        $jar = Join-Path $PSScriptRoot '..\koonj-fuse\target\koonj-fuse.jar'
    }
}
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Jar not found at $jar. Set KOONJ_FUSE_JAR or build it with mvn -pl koonj-fuse -am package."
}

$javaOptions = @('-Dfile.encoding=UTF-8')
if ($env:KOONJ_WINFSP_DLL) {
    $javaOptions += "-Djnr-fuse.windows.libpath=$env:KOONJ_WINFSP_DLL"
}
& java @javaOptions -jar $jar @args
exit $LASTEXITCODE
