param([string]$BaseUrl = 'http://127.0.0.1:18080')
. (Join-Path $PSScriptRoot 'common.ps1')

$k6 = (Get-Command k6.exe -ErrorAction SilentlyContinue)
if (-not $k6) { $k6 = Get-Command k6 -ErrorAction SilentlyContinue }
if (-not $k6) { throw 'k6 is not installed. Install k6 before running the API performance check.' }
$password = Get-Phase5EnvValue -Name 'DEMO1_PASSWORD' -Default 'MirrorDemo1!'
if ([string]::IsNullOrWhiteSpace($password)) { throw 'DEMO1_PASSWORD is missing from .env.' }
New-Item -ItemType Directory -Force -Path $script:Phase5ResultDir | Out-Null
$env:BASE_URL = $BaseUrl
$env:DEMO1_PASSWORD = $password
Push-Location $script:Phase5Repo
try {
    & $k6.Source run scripts/phase5/api-performance.js
    if ($LASTEXITCODE -ne 0) { throw 'API performance thresholds failed; see data/phase5/results/api-performance.json.' }
} finally {
    Pop-Location
    Remove-Item Env:\DEMO1_PASSWORD -ErrorAction SilentlyContinue
}
