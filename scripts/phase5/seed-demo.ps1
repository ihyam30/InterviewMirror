param([string]$ProjectName = 'interviewmirror-phase5')
. (Join-Path $PSScriptRoot 'common.ps1')

Assert-Phase5ProjectOnly -ProjectName $ProjectName
Set-Location $script:Phase5Repo
$env:DATABASE_PORT = '25432'
$env:MINIO_API_PORT = '19000'
$env:MINIO_CONSOLE_PORT = '19001'
$env:BACKEND_PORT = '18080'
$env:FRONTEND_PORT = '15173'
$status = @(& docker compose -p $script:Phase5Project ps --format json | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0) { throw 'Cannot query the isolated Phase 5 Compose project.' }
if (-not ($status | Where-Object { $_.Service -eq 'postgres' -and $_.Health -eq 'healthy' })) {
    throw 'The Phase 5 PostgreSQL service must be healthy before seeding.'
}
$sql = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'seed-demo.sql') -Raw
$dbName = Get-Phase5EnvValue -Name 'DATABASE_NAME' -Default 'interviewmirror'
$dbUser = Get-Phase5EnvValue -Name 'DATABASE_USER' -Default 'interviewmirror'
$sql | & docker compose -p $script:Phase5Project exec -T postgres psql -v ON_ERROR_STOP=1 -U $dbUser -d $dbName
if ($LASTEXITCODE -ne 0) { throw 'The idempotent demo seed failed.' }
Write-Host 'Seeded one confirmed synthetic resume and one confirmed 8-question bank for demo1.'
Write-Host 'The fixture data is synthetic, visibly marked as demo content, and contains no real candidate data.'
