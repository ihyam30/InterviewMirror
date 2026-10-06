param(
    [switch]$RunModelCalls,
    [ValidateRange(10, 30)][int]$Samples = 20,
    [double]$InputPriceCnyPerMillion = 0.8,
    [double]$OutputPriceCnyPerMillion = 2.0
)
. (Join-Path $PSScriptRoot 'common.ps1')

if (-not $RunModelCalls) {
    throw 'This test makes billable external model calls. Re-run with -RunModelCalls to confirm the requested live measurement; prompts contain synthetic text only.'
}
foreach ($name in @('INTERVIEW_MODEL_API_KEY', 'INTERVIEW_MODEL_BASE_URL', 'INTERVIEW_MODEL_ID')) {
    $value = Get-Phase5EnvValue -Name $name
    if ([string]::IsNullOrWhiteSpace($value) -or $value -match '^replace-with-') {
        throw "$name is missing from .env; no credential values were printed."
    }
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
[Environment]::SetEnvironmentVariable('INTERVIEW_MODEL_PROVIDER', (Get-Phase5EnvValue -Name 'INTERVIEW_MODEL_PROVIDER' -Default 'QWEN'), 'Process')
[Environment]::SetEnvironmentVariable('PHASE5_MODEL_SAMPLES', [string]$Samples, 'Process')
[Environment]::SetEnvironmentVariable('PHASE5_INPUT_PRICE_CNY_PER_MILLION', [string]$InputPriceCnyPerMillion, 'Process')
[Environment]::SetEnvironmentVariable('PHASE5_OUTPUT_PRICE_CNY_PER_MILLION', [string]$OutputPriceCnyPerMillion, 'Process')
New-Item -ItemType Directory -Force -Path $script:Phase5ResultDir | Out-Null
Push-Location $script:Phase5Repo
try {
    & node scripts/phase5/model-performance.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Live model performance acceptance failed. Inspect the sanitized results file; no credential value is written there.' }
} finally {
    Pop-Location
    foreach ($name in @('INTERVIEW_MODEL_API_KEY', 'INTERVIEW_MODEL_BASE_URL', 'INTERVIEW_MODEL_ID', 'INTERVIEW_MODEL_PROVIDER', 'PHASE5_MODEL_SAMPLES', 'PHASE5_INPUT_PRICE_CNY_PER_MILLION', 'PHASE5_OUTPUT_PRICE_CNY_PER_MILLION')) {
        Remove-Item "Env:\$name" -ErrorAction SilentlyContinue
    }
}
