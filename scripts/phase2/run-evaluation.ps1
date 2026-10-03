param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [int]$PerDocumentTimeout = 600,
    [string]$EnvFile = '.env'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo
$python = Join-Path $repo 'scripts\poc\.mineru\Scripts\python.exe'
$envPath = Join-Path $repo $EnvFile
if (-not (Test-Path -LiteralPath $python)) {
    throw 'Pinned MinerU Python runtime is missing. Follow docs/phase0/MINERU-POC.md.'
}
if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'Missing .env. Copy .env.example to .env and configure the local demo passwords.'
}

$line = Get-Content -LiteralPath $envPath | Where-Object { $_ -match '^\s*DEMO2_PASSWORD\s*=' } | Select-Object -Last 1
if (-not $line) { throw 'DEMO2_PASSWORD is missing from .env.' }
$env:DEMO2_PASSWORD = ($line -split '=', 2)[1].Trim().Trim('"').Trim("'")
if ([string]::IsNullOrWhiteSpace($env:DEMO2_PASSWORD)) {
    Remove-Item Env:DEMO2_PASSWORD -ErrorAction SilentlyContinue
    throw 'DEMO2_PASSWORD in .env is empty.'
}

try {
    & $python -u (Join-Path $PSScriptRoot 'evaluate_live.py') --base-url $BaseUrl --per-document-timeout $PerDocumentTimeout
    $result = $LASTEXITCODE
} finally {
    Remove-Item Env:DEMO2_PASSWORD -ErrorAction SilentlyContinue
}
exit $result
