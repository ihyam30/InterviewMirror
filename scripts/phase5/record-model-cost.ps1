param(
    [int]$MonthlyInterviewCount = 100,
    [double]$InputPriceCnyPerMillion = 0.8,
    [double]$OutputPriceCnyPerMillion = 2.0,
    [DateTimeOffset]$SinceUtc = [DateTimeOffset]::MinValue
)
. (Join-Path $PSScriptRoot 'common.ps1')

if ($MonthlyInterviewCount -lt 1 -or $MonthlyInterviewCount -gt 10000) { throw 'MonthlyInterviewCount must be between 1 and 10000.' }
Set-Location $script:Phase5Repo
$env:DATABASE_PORT = '25432'; $env:MINIO_API_PORT = '19000'; $env:MINIO_CONSOLE_PORT = '19001'; $env:BACKEND_PORT = '18080'
$measurementStart = if ($SinceUtc -eq [DateTimeOffset]::MinValue) { [DateTimeOffset]::UtcNow.AddDays(-1) } else { $SinceUtc.ToUniversalTime() }
$sinceArg = $measurementStart.ToString('o')
$sinceSql = $measurementStart.ToString("yyyy-MM-dd HH:mm:ss.fff+00")
$lines = @(& docker compose -p $script:Phase5Project logs --since $sinceArg --no-color backend)
if ($LASTEXITCODE -ne 0) { throw 'Could not read isolated Phase 5 backend logs.' }
$usagePattern = 'model_usage family=(\S+) operation=(\S+) provider=(\S+) model=(\S+) promptVersion=(\S+) elapsedMs=(\d+) inputTokens=(\S+) outputTokens=(\S+) usageReported=(true|false)'
$usage = @()
foreach ($line in $lines) {
    $match = [regex]::Match([string]$line, $usagePattern)
    if (-not $match.Success) { continue }
    $usage += [pscustomobject]@{
        family = $match.Groups[1].Value
        operation = $match.Groups[2].Value
        provider = $match.Groups[3].Value
        model = $match.Groups[4].Value
        elapsedMs = [long]$match.Groups[6].Value
        inputTokens = if ($match.Groups[7].Value -match '^\d+$') { [long]$match.Groups[7].Value } else { $null }
        outputTokens = if ($match.Groups[8].Value -match '^\d+$') { [long]$match.Groups[8].Value } else { $null }
        usageReported = $match.Groups[9].Value -eq 'true'
    }
}
$missingUsageCount = @($usage | Where-Object { -not $_.usageReported }).Count
$modelFailureCount = @($lines | Where-Object { $_ -match 'interview model call failed|report_llm operation=.*failure_type=' }).Count
$knownUsage = @($usage | Where-Object { $_.usageReported })
$inputTokens = [long](($knownUsage | Measure-Object -Property inputTokens -Sum).Sum)
$outputTokens = [long](($knownUsage | Measure-Object -Property outputTokens -Sum).Sum)
$modelIds = @($usage | Select-Object -ExpandProperty model -Unique)
$usageCost = [math]::Round($inputTokens * $InputPriceCnyPerMillion / 1000000 + $outputTokens * $OutputPriceCnyPerMillion / 1000000, 6)
$dbName = Get-Phase5EnvValue -Name 'DATABASE_NAME' -Default 'interviewmirror'
$dbUser = Get-Phase5EnvValue -Name 'DATABASE_USER' -Default 'interviewmirror'
$query = @"
SELECT COUNT(*) FILTER (WHERE i.status = 'COMPLETE'), COUNT(*) FILTER (WHERE t.status = 'SUCCESS'), COUNT(*) FILTER (WHERE t.status = 'FAILED')
FROM interviews i JOIN app_users u ON u.id = i.owner_id
LEFT JOIN report_tasks t ON t.interview_id = i.id AND t.task_type = 'REPORT'
WHERE u.username = 'demo1' AND i.created_at >= '$sinceSql'::timestamptz;
"@
$countsRaw = $query | & docker compose -p $script:Phase5Project exec -T postgres psql -q -A -t -F '|' -v ON_ERROR_STOP=1 -U $dbUser -d $dbName
if ($LASTEXITCODE -ne 0) { throw 'Could not count demo interview/report sessions for cost attribution.' }
$counts = ([string]$countsRaw).Trim() -split '\|'
if ($counts.Count -ne 3) { throw 'Unexpected interview/report usage counts.' }
$completedInterviews = [int]$counts[0]
$successfulReports = [int]$counts[1]
$failedReports = [int]$counts[2]
$averageCost = if ($completedInterviews -gt 0) { [math]::Round($usageCost / $completedInterviews, 6) } else { $null }
$monthlyCost = if ($null -ne $averageCost) { [math]::Round($averageCost * $MonthlyInterviewCount, 2) } else { $null }
$result = [ordered]@{
    measuredAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    syntheticOnly = $true
    sinceUtc = $measurementStart.ToString('o')
    modelIds = $modelIds
    usageCalls = $usage.Count
    usageMissingCount = $missingUsageCount
    modelFailureLogCount = $modelFailureCount
    completedInterviews = $completedInterviews
    successfulReports = $successfulReports
    failedReports = $failedReports
    inputTokens = $inputTokens
    outputTokens = $outputTokens
    inputPriceCnyPerMillion = $InputPriceCnyPerMillion
    outputPriceCnyPerMillion = $OutputPriceCnyPerMillion
    observedTotalCostCny = $usageCost
    averageCostPerCompletedInterviewCny = $averageCost
    assumedInterviewsPerMonth = $MonthlyInterviewCount
    estimatedMonthlyCostCny = $monthlyCost
    monthlyBudgetCny = 300
    budgetPass = ($null -ne $monthlyCost -and $monthlyCost -le 300 -and $missingUsageCount -eq 0 -and $modelFailureCount -eq 0 -and $failedReports -eq 0 -and $successfulReports -ge $completedInterviews)
    costCoverage = if ($usage.Count -eq 0 -or $missingUsageCount -gt 0 -or $modelFailureCount -gt 0 -or $successfulReports -lt $completedInterviews) { 'INCOMPLETE' } else { 'COMPLETE_FOR_SUCCESSFUL_LOGGED_WORKFLOWS' }
}
$path = Write-Phase5Json -Name 'model-cost.json' -Value $result
Write-Host "Model usage: calls=$($usage.Count) missingUsage=$missingUsageCount failures=$modelFailureCount inputTokens=$inputTokens outputTokens=$outputTokens averagePerInterview=$averageCost CNY monthlyAt$MonthlyInterviewCount=$monthlyCost CNY budgetPass=$($result.budgetPass). Record: $path"
if (-not $result.budgetPass) { throw 'Monthly cost is above budget or usage/report coverage is incomplete.' }
