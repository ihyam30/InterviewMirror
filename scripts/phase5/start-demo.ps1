param([int]$HealthTimeoutSeconds = 600)
. (Join-Path $PSScriptRoot 'common.ps1')

if (-not (Test-Path -LiteralPath (Join-Path $script:Phase5Repo '.env'))) {
    throw 'Missing .env. Copy .env.example and configure local-only demo credentials first.'
}
Set-Location $script:Phase5Repo
$startedAt = [DateTimeOffset]::UtcNow
$timer = [Diagnostics.Stopwatch]::StartNew()
$priorVolumes = @(& docker volume ls -q --filter "label=com.docker.compose.project=$script:Phase5Project")
Invoke-Phase5Compose -Arguments @('up', '--build', '-d')

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($HealthTimeoutSeconds)
do {
    $healthy = $true
    $rows = @(& docker compose -p $script:Phase5Project ps --format json | ConvertFrom-Json)
    foreach ($name in @('postgres', 'minio', 'backend', 'frontend')) {
        $row = $rows | Where-Object { $_.Service -eq $name } | Select-Object -First 1
        if (-not $row -or $row.State -notmatch 'running' -or $row.Health -notmatch 'healthy') { $healthy = $false }
    }
    if ($healthy) { break }
    if ([DateTimeOffset]::UtcNow -ge $deadline) {
        Invoke-Phase5Compose -Arguments @('ps')
        throw "Phase 5 services did not all become healthy within $HealthTimeoutSeconds seconds."
    }
    Start-Sleep -Seconds 5
} while ($true)
$timer.Stop()
$result = [ordered]@{
    startedAtUtc = $startedAt.ToString('o')
    startupDurationMs = $timer.ElapsedMilliseconds
    freshPhase5Volumes = ($priorVolumes.Count -eq 0)
    buildCacheStatus = 'warm-or-existing-cache; Docker/BuildKit caches were preserved'
    coldEnvironment15MinuteGate = 'NOT_MEASURED'
    project = $script:Phase5Project
    serviceHealth = 'postgres/minio/backend/frontend healthy'
}
$path = Write-Phase5Json -Name 'startup.json' -Value $result
Write-Host "Phase 5 local services are healthy. Frontend: http://127.0.0.1:15173"
Write-Host "Startup record: $path"
Write-Host 'This timing is a fresh isolated data volume with the machine build cache; it is not a cold-environment 15-minute result.'
