param([string]$MavenPath = '')
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envFile = Join-Path $repo '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'Missing .env. Copy .env.example, configure the model provider, then run this evaluation.'
}

$allowed = @(
    'INTERVIEW_MODEL_PROVIDER',
    'INTERVIEW_MODEL_BASE_URL',
    'INTERVIEW_MODEL_API_KEY',
    'INTERVIEW_MODEL_ID'
)
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^\s*#' -or $line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { continue }
    $name = $Matches[1]
    if ($name -notin $allowed) { continue }
    $value = $Matches[2].Trim()
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

if ([string]::IsNullOrWhiteSpace($env:INTERVIEW_MODEL_API_KEY) -or $env:INTERVIEW_MODEL_API_KEY -eq 'replace-with-a-provider-key') {
    throw 'INTERVIEW_MODEL_API_KEY is not configured. No credential values were printed.'
}
$env:INTERVIEW_MODEL_ENABLED = 'true'
$mavenCommand = $null
if (-not [string]::IsNullOrWhiteSpace($MavenPath)) {
    $resolvedMaven = Resolve-Path -LiteralPath $MavenPath -ErrorAction SilentlyContinue
    if (-not $resolvedMaven) { throw 'The provided Maven path does not exist.' }
    $mavenCommand = Get-Item -LiteralPath $resolvedMaven.Path
} else {
    $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $mavenCommand) { $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue }
}
if (-not $mavenCommand -and [string]::IsNullOrWhiteSpace($MavenPath)) {
    foreach ($entry in ($env:PATH -split [System.IO.Path]::PathSeparator)) {
        $candidate = Join-Path $entry 'mvn.cmd'
        if (Test-Path -LiteralPath $candidate) { $mavenCommand = Get-Item -LiteralPath $candidate; break }
    }
}
if (-not $mavenCommand) { throw 'Maven 3.9+ is not available in PATH. Install Maven and retry.' }
$mavenPath = if ($mavenCommand.Source) { $mavenCommand.Source } else { $mavenCommand.FullName }

Write-Host 'Running Phase 4 evaluation using synthetic fixtures only.'
Write-Host 'Maximum planned provider calls: 10 synthetic report generations plus summary/dimension evidence reviews as needed. Disabled gap analysis is never called.'
Write-Host 'Credentials are loaded from .env and will not be printed.'
Push-Location $repo
try {
    & $mavenPath -B -f backend/pom.xml '-Dphase4.model-eval=true' '-Dinterviewmirror.model.enabled=true' '-Dtest=Phase4ModelEvaluationTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Phase 4 model evaluation failed. See the Maven test report; secret values are not printed.' }
} finally {
    Pop-Location
}
