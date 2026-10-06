param([string]$ProjectName = 'interviewmirror-phase5')
. (Join-Path $PSScriptRoot 'common.ps1')

Assert-Phase5ProjectOnly -ProjectName $ProjectName
Set-Location $script:Phase5Repo
$frontendUrl = 'http://127.0.0.1:15173'
$backendUrl = 'http://127.0.0.1:18080'
$status = @(& docker compose -p $script:Phase5Project ps --format json | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0) { throw 'Cannot query the Phase 5 Compose project.' }
foreach ($service in @('postgres', 'minio', 'backend', 'frontend')) {
    $row = $status | Where-Object { $_.Service -eq $service } | Select-Object -First 1
    if (-not $row -or $row.Health -ne 'healthy') { throw "$service is not healthy." }
}
$health = Invoke-RestMethod -Uri "$backendUrl/actuator/health" -TimeoutSec 10
if ($health.status -ne 'UP') { throw 'Backend health endpoint did not return UP.' }
$null = Invoke-WebRequest -Uri $frontendUrl -TimeoutSec 10 -UseBasicParsing

$password = Get-Phase5EnvValue -Name 'DEMO1_PASSWORD' -Default 'MirrorDemo1!'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$csrf = Invoke-RestMethod -Method Get -Uri "$backendUrl/api/v1/auth/csrf" -WebSession $session -TimeoutSec 10
$loginBody = @{ identifier = 'demo1'; password = $password } | ConvertTo-Json -Compress
$headers = @{ 'X-XSRF-TOKEN' = $csrf.data.token }
$null = Invoke-RestMethod -Method Post -Uri "$backendUrl/api/v1/auth/login" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $loginBody -TimeoutSec 10
$user = Invoke-RestMethod -Method Get -Uri "$backendUrl/api/v1/auth/me" -WebSession $session -TimeoutSec 10
if ($user.data.username -ne 'demo1') { throw 'Demo login resolved to an unexpected user.' }
$loginReadyAtUtc = [DateTimeOffset]::UtcNow
$startupRecordPath = Join-Path $script:Phase5ResultDir 'startup.json'
$startupToLoginMs = $null
if (Test-Path -LiteralPath $startupRecordPath) {
    $startupRecord = Get-Content -LiteralPath $startupRecordPath -Raw | ConvertFrom-Json
    $startupStartedAt = [DateTimeOffset]::Parse($startupRecord.startedAtUtc).ToUniversalTime()
    $startupToLoginMs = [long]($loginReadyAtUtc - $startupStartedAt).TotalMilliseconds
}
$resumes = Invoke-RestMethod -Uri "$backendUrl/api/v1/resumes?usableOnly=true" -WebSession $session -TimeoutSec 10
$banks = Invoke-RestMethod -Uri "$backendUrl/api/v1/question-banks?usableOnly=true" -WebSession $session -TimeoutSec 10
$resume = @($resumes.data | Where-Object { $_.id -eq 'a5500000-0000-4000-8000-000000000001' -and $_.status -eq 'CONFIRMED' })
$bank = @($banks.data | Where-Object { $_.id -eq 'a5500000-0000-4000-8000-000000000002' -and $_.status -eq 'CONFIRMED' })
if (-not $resume -or -not $bank) { throw 'Run scripts/phase5/seed-demo.ps1 to create the confirmed synthetic sources.' }
$result = [ordered]@{
    checkedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    loginReadyAtUtc = $loginReadyAtUtc.ToString('o')
    startupToLoginMs = $startupToLoginMs
    startupCacheState = if (Test-Path -LiteralPath $startupRecordPath) { (Get-Content -LiteralPath $startupRecordPath -Raw | ConvertFrom-Json).buildCacheStatus } else { 'UNKNOWN' }
    project = $script:Phase5Project
    frontend = 'PASS'
    backend = 'PASS'
    postgres = 'PASS'
    minio = 'PASS'
    loginAs = 'demo1'
    confirmedSyntheticResume = 'PASS'
    confirmedSyntheticQuestionBank = 'PASS'
    reportHistory = 'No pre-seeded report; create a live demo interview to generate one.'
    modelConfigurationPresent = (Get-Phase5EnvValue -Name 'INTERVIEW_MODEL_ENABLED' -Default 'false') -eq 'true' -and -not [string]::IsNullOrWhiteSpace((Get-Phase5EnvValue -Name 'INTERVIEW_MODEL_API_KEY'))
    mineruWorker = 'NOT_REQUIRED_FOR_SEEDED_FIXTURES'
}
$path = Write-Phase5Json -Name 'demo-check.json' -Value $result
Write-Host "PASS: Phase 5 local services, demo login, and confirmed synthetic sources. Record: $path"
