$ErrorActionPreference = "Stop"
$src = Join-Path $PSScriptRoot "src"
$out = Join-Path $PSScriptRoot "out"
$jar = Join-Path $PSScriptRoot "sfus.jar"

function Find-JdkTool([string]$name) {
    if ($env:JAVA_HOME) {
        $p = Join-Path $env:JAVA_HOME "bin\$name.exe"
        if (Test-Path $p) { return $p }
    }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $known = "C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot\bin\$name.exe"
    if (Test-Path $known) { return $known }
    throw "$name not found - install a JDK 21+ or set JAVA_HOME"
}

$javac = Find-JdkTool "javac"
$jarTool = Find-JdkTool "jar"

New-Item -ItemType Directory -Force -Path $out | Out-Null
$files = Get-ChildItem $src -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
& $javac -d $out $files
if ($LASTEXITCODE -ne 0) { Write-Host "Build failed"; exit $LASTEXITCODE }

& $jarTool --create --file $jar --main-class mcbot.Main -C $out .
if ($LASTEXITCODE -ne 0) { Write-Host "Jar failed"; exit $LASTEXITCODE }

Write-Host "Build succeeded -> $jar"
