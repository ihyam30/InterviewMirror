param([string]$ProjectName = 'interviewmirror-phase5', [string]$Confirmation = '')
. (Join-Path $PSScriptRoot 'common.ps1')

Assert-Phase5ProjectOnly -ProjectName $ProjectName
if ($Confirmation -cne 'DELETE_INTERVIEWMIRROR_PHASE5_LOCAL_DATA') {
    throw "To reset only the isolated Phase 5 database and MinIO volumes, pass -Confirmation DELETE_INTERVIEWMIRROR_PHASE5_LOCAL_DATA. No other Docker project is affected."
}
Set-Location $script:Phase5Repo
Invoke-Phase5Compose -Arguments @('down', '--volumes', '--remove-orphans')
Write-Host "Removed containers and volumes owned only by Compose project '$script:Phase5Project'."
