$ErrorActionPreference = 'Stop'
$script:Phase5Project = 'interviewmirror-phase5'
$script:Phase5Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:Phase5ResultDir = Join-Path $script:Phase5Repo 'data\phase5\results'

function Set-Phase5ComposePorts {
    $env:DATABASE_PORT = '25432'
    $env:MINIO_API_PORT = '19000'
    $env:MINIO_CONSOLE_PORT = '19001'
    $env:BACKEND_PORT = '18080'
    $env:FRONTEND_PORT = '15173'
}

function Invoke-Phase5Compose {
    param([Parameter(Mandatory)][string[]]$Arguments)
    Set-Phase5ComposePorts
    & docker compose -p $script:Phase5Project @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed (exit $LASTEXITCODE)." }
}

function Get-Phase5EnvValue {
    param([Parameter(Mandatory)][string]$Name, [string]$Default = '')
    $path = Join-Path $script:Phase5Repo '.env'
    if (-not (Test-Path -LiteralPath $path)) { return $Default }
    $line = Get-Content -LiteralPath $path | Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } | Select-Object -Last 1
    if (-not $line) { return $Default }
    $value = ($line -split '=', 2)[1].Trim()
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        return $value.Substring(1, $value.Length - 2)
    }
    return $value
}

function Assert-Phase5ProjectOnly {
    param([string]$ProjectName)
    if ($ProjectName -cne $script:Phase5Project) {
        throw "This operation is restricted to Compose project '$script:Phase5Project'; no other project or volume will be touched."
    }
}

function Write-Phase5Json {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)]$Value)
    New-Item -ItemType Directory -Force -Path $script:Phase5ResultDir | Out-Null
    $path = Join-Path $script:Phase5ResultDir $Name
    $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $path -Encoding utf8
    return $path
}
