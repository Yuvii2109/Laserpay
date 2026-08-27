<#
.SYNOPSIS
    Start the PDEI local stack (Windows / PowerShell).

.DESCRIPTION
    Equivalent of scripts/up.sh. Startup ordering is enforced by depends_on/service_healthy
    in infra/docker-compose.yml, not by sleeps here.

.PARAMETER Profiles
    Any of core, app, obs (or all). Defaults to core+app+obs.

.PARAMETER Build
    Force a rebuild of the images built from this repository.

.PARAMETER NoWait
    Return as soon as the containers are created, without polling the UIs.

.EXAMPLE
    .\scripts\up.ps1
.EXAMPLE
    .\scripts\up.ps1 -Profiles core
.EXAMPLE
    .\scripts\up.ps1 -Profiles core,app -Build
#>
[CmdletBinding()]
param(
    [ValidateSet('core', 'app', 'obs', 'all')]
    [string[]]$Profiles = @('core', 'app', 'obs'),
    [switch]$Build,
    [switch]$NoWait
)

. "$PSScriptRoot\lib.ps1"

$ErrorActionPreference = 'Stop'

Assert-Docker
Initialize-EnvFile

$flags = Get-ProfileFlags -Profiles $Profiles
$upArgs = $flags + @('up', '-d', '--remove-orphans')
if ($Build) { $upArgs += '--build' }

Write-Pdei ("starting profiles: " + ($Profiles -join ', '))
$exit = Invoke-Compose @upArgs
if ($exit -ne 0) {
    Write-PdeiErr "docker compose up exited with $exit"
    exit $exit
}

if ($NoWait) {
    Write-PdeiOk 'containers created (not waiting for health)'
    exit 0
}

$wantCore = ($Profiles -contains 'core') -or ($Profiles -contains 'all')
$wantApp  = ($Profiles -contains 'app')  -or ($Profiles -contains 'all')
$wantObs  = ($Profiles -contains 'obs')  -or ($Profiles -contains 'all')

if ($wantCore) {
    Wait-HttpEndpoint -Url 'http://localhost:8090/actuator/health' -Label 'Kafka UI'    -Attempts 60 | Out-Null
    Wait-HttpEndpoint -Url 'http://localhost:9000/minio/health/live' -Label 'MinIO'     -Attempts 60 | Out-Null
    Wait-HttpEndpoint -Url 'http://localhost:8233/' -Label 'Temporal UI'                -Attempts 90 | Out-Null
}
if ($wantApp) {
    Wait-HttpEndpoint -Url 'http://localhost:8080/actuator/health' -Label 'api-gateway-service'  -Attempts 120 | Out-Null
    Wait-HttpEndpoint -Url 'http://localhost:8000/health'          -Label 'ai-reasoning-service' -Attempts 90  | Out-Null
    Wait-HttpEndpoint -Url 'http://localhost:3000/api/health'      -Label 'frontend'             -Attempts 120 | Out-Null
}
if ($wantObs) {
    Wait-HttpEndpoint -Url 'http://localhost:3001/api/health' -Label 'Grafana' -Attempts 90 | Out-Null
}

Write-Host ''
$psArgs = $flags + @('ps', '--format', 'table {{.Service}}\t{{.Status}}\t{{.Ports}}')
Invoke-Compose @psArgs | Out-Host
Write-Host ''
Write-PdeiOk 'stack is up. Next: .\scripts\smoke-test.ps1'
