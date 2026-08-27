# PDEI - shared PowerShell helpers (Windows PowerShell 5.1 compatible).
#
# Dot-source it:   . "$PSScriptRoot\lib.ps1"
#
# Deliberately avoids PowerShell 7 syntax (&&, ||, ternary, ??, ?.) because the primary
# dev machine runs Windows PowerShell 5.1.

Set-StrictMode -Version 2.0

$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$script:InfraDir = Join-Path $RepoRoot 'infra'

function Write-Pdei {
    param([string]$Message, [string]$Color = 'Cyan')
    Write-Host '[pdei] ' -ForegroundColor $Color -NoNewline
    Write-Host $Message
}
function Write-PdeiOk   { param([string]$m) Write-Pdei $m 'Green' }
function Write-PdeiWarn { param([string]$m) Write-Pdei $m 'Yellow' }
function Write-PdeiErr  { param([string]$m) Write-Pdei $m 'Red' }

function Assert-Docker {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        throw 'docker is not on PATH. Install Docker Desktop and reopen the terminal.'
    }
    docker compose version > $null 2>&1
    if (-not $?) { throw 'docker compose v2 is not available (docker-compose v1 is not supported).' }
    docker info > $null 2>&1
    if (-not $?) { throw 'the Docker daemon is not reachable. Start Docker Desktop and try again.' }
}

function Initialize-EnvFile {
    $envFile = Join-Path $InfraDir '.env'
    $example  = Join-Path $InfraDir '.env.example'
    if (-not (Test-Path $envFile)) {
        Copy-Item $example $envFile
        Write-Pdei 'created infra\.env from .env.example'
    }
}

# Runs docker compose from infra\ so docker-compose.yml, the override file and .env are
# all picked up implicitly. Arguments are passed through verbatim.
function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArgs)
    Push-Location $InfraDir
    try {
        & docker compose @ComposeArgs
        return $LASTEXITCODE
    } finally {
        Pop-Location
    }
}

function Get-ProfileFlags {
    param([string[]]$Profiles)
    $flags = @()
    foreach ($p in $Profiles) {
        switch ($p) {
            'core' { $flags += @('--profile', 'core') }
            'app'  { $flags += @('--profile', 'app') }
            'obs'  { $flags += @('--profile', 'obs') }
            'all'  { $flags += @('--profile', 'core', '--profile', 'app', '--profile', 'obs') }
            default { throw "unknown profile '$p' (expected: core, app, obs, all)" }
        }
    }
    return $flags
}

# Returns a PSCustomObject: Code (int), Milliseconds (int), Error (string).
function Test-HttpEndpoint {
    param([string]$Url, [int]$TimeoutSec = 5)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $code = 0
    $errText = ''
    try {
        $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec -Method Get -ErrorAction Stop
        $code = [int]$resp.StatusCode
    } catch {
        $response = $null
        if ($null -ne $_.Exception.PSObject.Properties['Response']) { $response = $_.Exception.Response }
        if ($null -ne $response) {
            try { $code = [int]$response.StatusCode } catch { $code = 0 }
        }
        if ($code -eq 0) { $errText = $_.Exception.Message }
    }
    $sw.Stop()
    return [PSCustomObject]@{
        Code         = $code
        Milliseconds = [int]$sw.ElapsedMilliseconds
        Error        = $errText
    }
}

function Wait-HttpEndpoint {
    param([string]$Url, [string]$Label, [int]$Attempts = 60, [int]$DelaySec = 2)
    Write-Pdei "waiting for $Label at $Url ..."
    for ($i = 0; $i -lt $Attempts; $i++) {
        $r = Test-HttpEndpoint -Url $Url -TimeoutSec 3
        if ($r.Code -ge 200 -and $r.Code -lt 400) {
            Write-PdeiOk "$Label is up"
            return $true
        }
        Start-Sleep -Seconds $DelaySec
    }
    Write-PdeiWarn "$Label did not become healthy in time"
    return $false
}

# Runs a command inside a container; returns $true when it exits 0.
function Test-ContainerCommand {
    param([string]$Container, [string[]]$Command)
    docker exec $Container @Command > $null 2>&1
    return ($LASTEXITCODE -eq 0)
}

function Test-ContainerRunning {
    param([string]$Container)
    $names = docker ps --format '{{.Names}}' 2>$null
    if ($null -eq $names) { return $false }
    return ($names -contains $Container)
}
