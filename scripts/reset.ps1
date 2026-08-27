<#
.SYNOPSIS
    Destroy the PDEI local environment and start from nothing (Windows / PowerShell).

.DESCRIPTION
    Equivalent of scripts/reset.sh.

    Deletes: every pdei container, every named volume (postgres including the Temporal
    databases, kafka log dirs, minio buckets and object versions, prometheus TSDB, grafana
    state, loki chunks, tempo blocks, promtail positions) and the pdei-net network.

    Does NOT delete: infra\.env, your override file, built images (unless -Images), or
    anything in your source tree.

    Reach for this when Kafka refuses to start after a cluster-id change, Flyway is wedged
    on a partial migration, or you want the demo seed to be the only data in the system.

.PARAMETER Force
    Skip the confirmation prompt.

.PARAMETER Up
    Bring the stack back up afterwards.

.PARAMETER Images
    Also remove the pdei/* images built from this repository.

.EXAMPLE
    .\scripts\reset.ps1
.EXAMPLE
    .\scripts\reset.ps1 -Force -Up
#>
[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$Up,
    [switch]$Images
)

. "$PSScriptRoot\lib.ps1"

$ErrorActionPreference = 'Stop'
Assert-Docker

if (-not $Force) {
    Write-Host 'This deletes ALL local PDEI data (postgres, kafka, minio, metrics, logs, traces).' -ForegroundColor Yellow
    $answer = Read-Host 'Type reset to confirm'
    if ($answer -ne 'reset') {
        Write-PdeiErr 'aborted - nothing was deleted'
        exit 1
    }
}

$flags = Get-ProfileFlags -Profiles @('all')

Write-Pdei 'removing containers and volumes'
$downArgs = $flags + @('down', '--volumes', '--remove-orphans', '--timeout', '20')
Invoke-Compose @downArgs | Out-Host

# down --volumes only removes volumes compose still tracks; the compose file pins explicit
# volume names, so sweep those by name as well.
$volumes = @(
    'pdei-postgres-data',
    'pdei-redis-data',
    'pdei-kafka-data',
    'pdei-minio-data',
    'pdei-prometheus-data',
    'pdei-grafana-data',
    'pdei-loki-data',
    'pdei-tempo-data',
    'pdei-promtail-data'
)
foreach ($v in $volumes) {
    docker volume inspect $v > $null 2>&1
    if ($LASTEXITCODE -eq 0) {
        docker volume rm -f $v > $null 2>&1
        if ($LASTEXITCODE -eq 0) { Write-Pdei "removed volume $v" }
    }
}

docker network inspect pdei-net > $null 2>&1
if ($LASTEXITCODE -eq 0) {
    docker network rm pdei-net > $null 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Pdei 'removed network pdei-net'
    } else {
        Write-PdeiWarn 'pdei-net still in use; it will be recreated on next up'
    }
}

if ($Images) {
    Write-Pdei 'removing images built from this repo'
    $imgs = docker images --format '{{.Repository}}:{{.Tag}}' 2>$null | Where-Object { $_ -like 'pdei/*' }
    foreach ($i in $imgs) { docker rmi -f $i > $null 2>&1 }
    Write-PdeiOk 'repo images removed - next up will rebuild them'
}

Write-PdeiOk 'reset complete - the environment is empty'

if ($Up) {
    Write-Pdei 'bringing the stack back up'
    & "$PSScriptRoot\up.ps1"
} else {
    Write-Pdei 'next: .\scripts\up.ps1    then    bash scripts/seed-demo.sh'
}
