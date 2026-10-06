param([int]$RequiredRuns = 3)
. (Join-Path $PSScriptRoot 'common.ps1')

if ($RequiredRuns -lt 1 -or $RequiredRuns -gt 10) { throw 'RequiredRuns must be between 1 and 10.' }
Set-Location $script:Phase5Repo
$env:DATABASE_PORT = '25432'; $env:MINIO_API_PORT = '19000'; $env:MINIO_CONSOLE_PORT = '19001'; $env:BACKEND_PORT = '18080'; $env:FRONTEND_PORT = '15173'
$dbName = Get-Phase5EnvValue -Name 'DATABASE_NAME' -Default 'interviewmirror'
$dbUser = Get-Phase5EnvValue -Name 'DATABASE_USER' -Default 'interviewmirror'
$query = @"
SELECT i.id, i.mode, i.created_at, i.started_at, i.completed_at
FROM interviews i JOIN app_users u ON u.id = i.owner_id
WHERE u.username = 'demo1' AND i.status = 'COMPLETE' AND i.completed_at >= CURRENT_TIMESTAMP - INTERVAL '1 day'
ORDER BY i.completed_at DESC LIMIT $RequiredRuns;
"@
$raw = $query | & docker compose -p $script:Phase5Project exec -T postgres psql -q -A -t -F '|' -v ON_ERROR_STOP=1 -U $dbUser -d $dbName
if ($LASTEXITCODE -ne 0) { throw 'Could not query completed demo interviews.' }
$runs = @()
foreach ($line in @($raw)) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split '\|', 5
    if ($parts.Count -ne 5) { throw 'Unexpected demo run timing row.' }
    $created = [DateTimeOffset]::Parse($parts[2]).ToUniversalTime()
    $completed = [DateTimeOffset]::Parse($parts[4]).ToUniversalTime()
    $runs += [pscustomobject]@{
        interviewId = $parts[0]
        mode = $parts[1]
        createdAtUtc = $created.ToString('o')
        startedAtUtc = $parts[3]
        completedAtUtc = $completed.ToString('o')
        endToEndMinutes = [math]::Round(($completed - $created).TotalMinutes, 2)
        tenMinuteGate = if (($completed - $created).TotalMinutes -le 10) { 'PASS' } else { 'FAIL' }
    }
}
if ($runs.Count -lt $RequiredRuns) {
    throw "Found $($runs.Count) completed demo runs in the last 24 hours; need $RequiredRuns. Complete more synthetic-data demo runs first."
}
$result = [ordered]@{
    measuredAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    owner = 'demo1'
    syntheticOnly = $true
    requiredRuns = $RequiredRuns
    actualRuns = $runs.Count
    allWithinTenMinutes = @($runs | Where-Object { $_.tenMinuteGate -eq 'PASS' }).Count -eq $runs.Count
    runs = $runs
}
$path = Write-Phase5Json -Name 'demo-runs.json' -Value $result
Write-Host "Recorded $($runs.Count) actual completed demo interviews from PostgreSQL. Timing record: $path"
if (-not $result.allWithinTenMinutes) { throw 'At least one complete demo run exceeded 10 minutes.' }
