param(
    [string]$Username = "AIBot",
    [string]$Host = "51.195.191.92",
    [int]$Port = 10679,
    [string]$Password = "ChangeMe123",
    [string]$ApiKey = $env:OLLAMA_API_KEY,
    [string]$Goto = $env:BOT_GOTO,
    [switch]$DebugLog
)
if ($Goto) { $env:BOT_GOTO = $Goto } else { Remove-Item Env:BOT_GOTO -ErrorAction SilentlyContinue }
if ($DebugLog) { $env:BOT_DEBUG = "1" } else { Remove-Item Env:BOT_DEBUG -ErrorAction SilentlyContinue }
$java = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path $candidate) { $java = $candidate }
}
if (-not $java) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $java = $cmd.Source }
}
if (-not $java) { throw "java not found - install a JDK 21+ or set JAVA_HOME" }
& $java -cp (Join-Path $PSScriptRoot "out") mcbot.Main $Username $Host $Port $Password $ApiKey
