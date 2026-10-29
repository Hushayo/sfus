param(
    [string]$Username = "AIBot",
    [string]$Host = "51.195.191.92",
    [int]$Port = 10679,
    [string]$Password = "ChangeMe123",
    # The registry, not $env:, is the current value: a shell opened before
    # `setx OLLAMA_API_KEY` (or the Environment Variables dialog) still carries
    # the old, empty environment block, and that is how the bot ended up saying
    # "chat is disabled" with the key plainly set. OllamaClient reads the same
    # fallback, so this is belt and braces.
    [string]$ApiKey = $(if ($env:OLLAMA_API_KEY) { $env:OLLAMA_API_KEY } else {
        (Get-ItemProperty -Path 'HKCU:\Environment' -Name 'OLLAMA_API_KEY' -ErrorAction SilentlyContinue).OLLAMA_API_KEY
    }),
    [string]$Goto = $env:BOT_GOTO,
    [switch]$DebugLog
)
if ($Goto) { $env:BOT_GOTO = $Goto } else { Remove-Item Env:BOT_GOTO -ErrorAction SilentlyContinue }
if ($DebugLog) { $env:BOT_DEBUG = "1" } else { Remove-Item Env:BOT_DEBUG -ErrorAction SilentlyContinue }
# One log file for every launch path; Main tees stdout/stderr into it.
$env:BOT_LOG = Join-Path $PSScriptRoot "logs\bot.log"
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
