<#
.SYNOPSIS
    Stop the PDEI local stack (Windows / PowerShell). Volumes are preserved.

.DESCRIPTION
    Equivalent of scripts/down.sh. Named volumes survive, so the next up.ps1 finds the same
    world. Use reset.ps1 for a clean slate.

.PARAMETER StopOnly
    Stop the containers without removing them - the fastest restart.

.PARAMETER Volumes
    Also delete the data volumes. Prefer reset.ps1, which is explicit about what it wipes.

.EXAMPLE
    .\scripts\down.ps1
.EXAMPLE
    .\scripts\down.ps1 -StopOnly
#>
[CmdletBinding()]
param(
    [switch]$StopOnly,
    [switch]$Volumes
)

. "$PSScriptRoot\lib.ps1"

$ErrorActionPreference = 'Stop'
Assert-Docker

# All profiles, so compose sees every service and nothing is left behind as an orphan.
$flags = Get-ProfileFlags -Profiles @('all')

if ($StopOnly) {
    Write-Pdei 'stopping containers (keeping them for a fast restart)'
    $stopArgs = $flags + @('stop')
    Invoke-Compose @stopArgs | Out-Host
    Write-PdeiOk 'stopped. Restart with .\scripts\up.ps1'
    exit 0
}

if ($Volumes) {
    Write-PdeiWarn 'this will DELETE all local data (postgres, kafka, minio, grafana, loki, tempo)'
    $downArgs = $flags + @('down', '--volumes', '--remove-orphans')
    Invoke-Compose @downArgs | Out-Host
    Write-PdeiOk 'stack down, volumes removed'
    exit 0
}

Write-Pdei 'stopping and removing containers (volumes preserved)'
$downArgs = $flags + @('down', '--remove-orphans')
Invoke-Compose @downArgs | Out-Host
Write-PdeiOk 'stack down. Data is still on disk - .\scripts\reset.ps1 wipes it.'
