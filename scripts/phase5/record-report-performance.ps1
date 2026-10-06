param(
    [int]$MinimumSamples = 3,
    [DateTimeOffset]$SinceUtc = [DateTimeOffset]::MinValue
)
. (Join-Path $PSScriptRoot 'common.ps1')

if ($MinimumSamples -lt 1 -or $MinimumSamples -gt 10) { throw 'MinimumSamples must be between 1 and 10.' }
Set-Location $script:Phase5Repo
$env:DATABASE_PORT = '25432'; $env:MINIO_API_PORT = '19000'; $env:MINIO_CONSOLE_PORT = '19001'; $env:BACKEND_PORT = '18080'
$measurementStart = if ($SinceUtc -eq [DateTimeOffset]::MinValue) { [DateTimeOffset]::UtcNow.AddDays(-1) } else { $SinceUtc.ToUniversalTime() }
$measurementStartSql = $measurementStart.ToString("yyyy-MM-dd HH:mm:ss.fff+00")
$dbName = Get-Phase5EnvValue -Name 'DATABASE_NAME' -Default 'interviewmirror'
$dbUser = Get-Phase5EnvValue -Name 'DATABASE_USER' -Default 'interviewmirror'
$query = @"
SELECT COUNT(*) FILTER (WHERE t.completed_at IS NOT NULL AND t.duration_ms IS NOT NULL),
       COUNT(*) FILTER (WHERE t.status = 'SUCCESS'),
       COUNT(*) FILTER (WHERE t.status = 'FAILED'),
       COALESCE(ROUND(PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY t.duration_ms)::numeric), 0),
       COALESCE(ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY t.duration_ms)::numeric), 0),
       COALESCE(MAX(t.duration_ms), 0)
FROM report_tasks t JOIN app_users u ON u.id = t.owner_id
WHERE u.username = 'demo1' AND t.task_type = 'REPORT' AND t.created_at >= '$measurementStartSql'::timestamptz;
"@
$raw = $query | & docker compose -p $script:Phase5Project exec -T postgres psql -q -A -t -F '|' -v ON_ERROR_STOP=1 -U $dbUser -d $dbName
if ($LASTEXITCODE -ne 0) { throw 'Could not query Phase 5 report task timings.' }
$parts = ([string]$raw).Trim() -split '\|'
if ($parts.Count -ne 6) { throw 'Unexpected report performance query result.' }
$sampleCount = [int]$parts[0]
$result = [ordered]@{
    measuredAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    sinceUtc = $measurementStart.ToString('o')
    syntheticOnly = $true
    source = 'report_tasks.duration_ms: production report worker, including model calls and database persistence'
    sampleCount = $sampleCount
    successCount = [int]$parts[1]
    failureCount = [int]$parts[2]
    p50Ms = [long]$parts[3]
    p95Ms = [long]$parts[4]
    maxMs = [long]$parts[5]
    p95UnderSixtySeconds = ([long]$parts[4] -le 60000)
}
$path = Write-Phase5Json -Name 'report-performance.json' -Value $result
Write-Host "Report worker measured since $($result.sinceUtc): samples=$sampleCount successes=$($result.successCount) failures=$($result.failureCount) p50=$($result.p50Ms)ms p95=$($result.p95Ms)ms max=$($result.maxMs)ms. Record: $path"
if ($sampleCount -lt $MinimumSamples) { throw "Only $sampleCount completed report attempts are available; need $MinimumSamples." }
if ($result.failureCount -gt 0 -or -not $result.p95UnderSixtySeconds) { throw 'Report performance acceptance failed.' }
