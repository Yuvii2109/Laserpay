<#
.SYNOPSIS
    Health-check every PDEI component and print a table (Windows / PowerShell).

.DESCRIPTION
    Equivalent of scripts/smoke-test.sh. Ports and health paths come from
    docs/PLATFORM-CONTRACT.md section 2; if a row here disagrees with that table, that
    table is right.

    The exit code is the number of failed checks, so CI can gate on it.

.PARAMETER Groups
    Any of core, app, obs. Defaults to all three.

.PARAMETER WaitSeconds
    Retry a failing probe for this long before recording it as DOWN.

.EXAMPLE
    .\scripts\smoke-test.ps1
.EXAMPLE
    .\scripts\smoke-test.ps1 -Groups app -WaitSeconds 60
#>
[CmdletBinding()]
param(
    [ValidateSet('core', 'app', 'obs')]
    [string[]]$Groups = @('core', 'app', 'obs'),
    [int]$WaitSeconds = 0,
    [switch]$Quiet
)

. "$PSScriptRoot\lib.ps1"

$ErrorActionPreference = 'Continue'
Assert-Docker

$script:Rows = New-Object System.Collections.ArrayList
$script:Failures = 0

function Add-Row {
    param([string]$Group, [string]$Component, [string]$Endpoint, [string]$Status, [string]$Detail, [string]$Latency)
    [void]$script:Rows.Add([PSCustomObject]@{
        GROUP     = $Group
        COMPONENT = $Component
        ENDPOINT  = $Endpoint
        STATUS    = $Status
        LATENCY   = $Latency
        DETAIL    = $Detail
    })
    if ($Status -ne 'UP') { $script:Failures++ }
    if (-not $Quiet) {
        if ($Status -eq 'UP') {
            Write-Host '  OK ' -ForegroundColor Green -NoNewline
            Write-Host $Component
        } else {
            Write-Host '  !! ' -ForegroundColor Red -NoNewline
            Write-Host "$Component  " -NoNewline
            Write-Host $Detail -ForegroundColor DarkGray
        }
    }
}

function Check-Http {
    param([string]$Group, [string]$Component, [string]$Url, [int]$MinCode = 200, [int]$MaxCode = 299)
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $r = $null
    while ($true) {
        $r = Test-HttpEndpoint -Url $Url -TimeoutSec 5
        if ($r.Code -ge $MinCode -and $r.Code -le $MaxCode) {
            Add-Row $Group $Component $Url 'UP' ("HTTP " + $r.Code) ("" + $r.Milliseconds + "ms")
            return
        }
        if ((Get-Date) -ge $deadline) { break }
        Start-Sleep -Seconds 2
    }
    $detail = "HTTP " + $r.Code
    if ($r.Code -eq 0) { $detail = 'no response (container down or port not published)' }
    Add-Row $Group $Component $Url 'DOWN' $detail ("" + $r.Milliseconds + "ms")
}

function Check-Exec {
    param([string]$Group, [string]$Component, [string]$Container, [string[]]$Command)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $okRun = Test-ContainerCommand -Container $Container -Command $Command
    $sw.Stop()
    if ($okRun) {
        Add-Row $Group $Component "docker exec $Container" 'UP' 'exec ok' ("" + [int]$sw.ElapsedMilliseconds + "ms")
        return
    }
    if (-not (Test-ContainerRunning -Container $Container)) {
        Add-Row $Group $Component "docker exec $Container" 'DOWN' 'container not running' '-'
    } else {
        Add-Row $Group $Component "docker exec $Container" 'DOWN' 'command failed inside container' '-'
    }
}

Write-Host ''
Write-Host 'PDEI smoke test' -ForegroundColor White -NoNewline
Write-Host ("  " + (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')) -ForegroundColor DarkGray
Write-Host ''

# =========================================================== core infrastructure
if ($Groups -contains 'core') {
    if (-not $Quiet) { Write-Pdei 'core infrastructure' }
    Check-Exec 'core' 'postgres'      'pdei-postgres' @('pg_isready', '-U', 'pdei', '-d', 'pdei')
    Check-Exec 'core' 'redis'         'pdei-redis'    @('redis-cli', 'ping')
    Check-Exec 'core' 'kafka'         'pdei-kafka'    @('/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:9092', '--list')
    Check-Http 'core' 'kafka-ui'      'http://localhost:8090/actuator/health'
    Check-Http 'core' 'minio'         'http://localhost:9000/minio/health/live'
    Check-Http 'core' 'minio-console' 'http://localhost:9001/' 200 399
    Check-Exec 'core' 'temporal'      'pdei-temporal-admin-tools' @('temporal', 'operator', 'cluster', 'health', '--address', 'temporal:7233')
    Check-Http 'core' 'temporal-ui'   'http://localhost:8233/' 200 399
}

# =========================================================== application services
if ($Groups -contains 'app') {
    if (-not $Quiet) { Write-Host ''; Write-Pdei 'application services' }
    Check-Http 'app' 'api-gateway-service'        'http://localhost:8080/actuator/health'
    Check-Http 'app' 'ingestion-service'          'http://localhost:8081/actuator/health'
    Check-Http 'app' 'normalization-worker'       'http://localhost:8082/actuator/health'
    Check-Http 'app' 'state-builder-worker'       'http://localhost:8083/actuator/health'
    Check-Http 'app' 'readiness-worker'           'http://localhost:8084/actuator/health'
    Check-Http 'app' 'case-orchestrator-service'  'http://localhost:8085/actuator/health'
    Check-Http 'app' 'document-processor-service' 'http://localhost:8086/actuator/health'
    Check-Http 'app' 'audit-service'              'http://localhost:8087/actuator/health'
    Check-Http 'app' 'simulator-service'          'http://localhost:8088/actuator/health'
    Check-Http 'app' 'ai-reasoning-service'       'http://localhost:8000/health'
    Check-Http 'app' 'frontend'                   'http://localhost:3000/api/health'
    Check-Http 'app' 'gateway /health/ready'      'http://localhost:8080/api/v1/health/ready'
}

# =========================================================== observability
if ($Groups -contains 'obs') {
    if (-not $Quiet) { Write-Host ''; Write-Pdei 'observability' }
    Check-Http 'obs' 'otel-collector' 'http://localhost:13133/'
    Check-Http 'obs' 'prometheus'     'http://localhost:9090/-/healthy'
    Check-Http 'obs' 'grafana'        'http://localhost:3001/api/health'
    Check-Http 'obs' 'loki'           'http://localhost:3100/ready'
    Check-Http 'obs' 'tempo'          'http://localhost:3200/ready'
}

# =========================================================== table
Write-Host ''
$script:Rows | Format-Table -AutoSize -Property GROUP, COMPONENT, ENDPOINT, STATUS, LATENCY, DETAIL | Out-Host

if ($script:Failures -eq 0) {
    Write-PdeiOk ("all " + $script:Rows.Count + " checks passed")
} else {
    Write-PdeiErr ("" + $script:Failures + " of " + $script:Rows.Count + " checks failed")
    Write-Host ''
    Write-Host 'First things to try' -ForegroundColor White
    Write-Host '  1. Still starting?      docker compose -f infra\docker-compose.yml ps'
    Write-Host '  2. Look at one service: bash scripts/logs.sh <service>'
    Write-Host '  3. Profile not enabled? .\scripts\up.ps1 -Profiles core,app,obs'
    Write-Host '  4. Port already taken?  override it in infra\.env (PDEI_*_HOST_PORT)'
    Write-Host '  5. Nuclear option:      .\scripts\reset.ps1 -Force -Up'
}

exit $script:Failures
