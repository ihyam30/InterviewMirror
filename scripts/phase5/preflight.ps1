. (Join-Path $PSScriptRoot 'common.ps1')

$checks = [System.Collections.Generic.List[object]]::new()
function Add-Check([string]$Name, [bool]$Passed, [string]$Detail) {
    $script:checks.Add([pscustomobject]@{ Name = $Name; Result = if ($Passed) { 'PASS' } else { 'FAIL' }; Detail = $Detail })
}

$envPath = Join-Path $script:Phase5Repo '.env'
Add-Check 'local .env' (Test-Path -LiteralPath $envPath) 'Required; values are never printed.'
try {
    $dockerVersion = (& docker info --format '{{.ServerVersion}}' 2>$null | Select-Object -First 1)
    Add-Check 'Docker Engine' (-not [string]::IsNullOrWhiteSpace($dockerVersion)) 'Docker daemon reachable.'
} catch { Add-Check 'Docker Engine' $false 'Docker daemon unreachable.' }
try {
    & docker compose version --short *> $null
    Add-Check 'Docker Compose v2' ($LASTEXITCODE -eq 0) 'Compose plugin available.'
} catch { Add-Check 'Docker Compose v2' $false 'Compose plugin unavailable.' }
try {
    Set-Location $script:Phase5Repo
    & docker compose -p $script:Phase5Project config --quiet *> $null
    Add-Check 'Compose config' ($LASTEXITCODE -eq 0) 'Phase 5 isolated project configuration parses.'
} catch { Add-Check 'Compose config' $false 'Compose configuration failed.' }

foreach ($port in @(15173, 18080, 25432, 19000, 19001)) {
    $inUse = [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    Add-Check "port $port" (-not $inUse) $(if ($inUse) { 'Already in use; choose free isolated ports before starting.' } else { 'Available.' })
}
$modelEnabled = (Get-Phase5EnvValue -Name 'INTERVIEW_MODEL_ENABLED' -Default 'false') -eq 'true'
$modelKey = Get-Phase5EnvValue -Name 'INTERVIEW_MODEL_API_KEY'
$modelReady = $modelEnabled -and -not [string]::IsNullOrWhiteSpace($modelKey) -and $modelKey -notmatch '^replace-with-'
Add-Check 'model configuration' $modelReady $(if ($modelReady) { 'Enabled and credential present; no value shown.' } else { 'Interview generation and model performance require local provider configuration.' })
$mineruToken = Get-Phase5EnvValue -Name 'MINERU_WORKER_TOKEN'
$mineruReady = -not [string]::IsNullOrWhiteSpace($mineruToken) -and $mineruToken.Length -ge 32 -and $mineruToken -notmatch '^replace-with-'
Add-Check 'MinerU token' $mineruReady $(if ($mineruReady) { 'Configured; no value shown.' } else { 'Document parsing worker requires a unique local token of at least 32 characters.' })
try {
    $worker = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/healthz' -TimeoutSec 3
    Add-Check 'MinerU Worker' ($worker.status -eq 'ok') "Health status: $($worker.status); version $($worker.mineruVersion)."
} catch { Add-Check 'MinerU Worker' $false 'Not reachable on loopback port 8765.' }

$checks | Format-Table -AutoSize
if ($checks.Result -contains 'FAIL') { exit 1 }
Write-Host 'Preflight passed. Provider and Worker credential values were not printed.'
