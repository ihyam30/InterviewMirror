param([switch]$KeepPostgres, [string]$PostgresImage = 'postgres:16-alpine')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$gitHead = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot record Git HEAD for the LangGraph4j PoC.' }
$gitDirty = [bool](& git -C $repo status --porcelain)
$pgName = "im-lg4j-poc-$PID"
$pgStarted = $false

function Invoke-MavenInContainer([string[]]$MavenArgs) {
    $dockerArgs = @(
        'run', '--rm',
        '-v', "$repo`:/workspace",
        '-v', "$repo/scripts/poc/.m2`:/root/.m2",
        '-w', '/workspace/scripts/poc/langgraph4j',
        '-e', 'LANGGRAPH_POC_DB_HOST=host.docker.internal',
        '-e', 'LANGGRAPH_POC_DB_PORT=55432',
        '-e', 'LANGGRAPH_POC_DB_NAME=interviewmirror_poc',
        '-e', 'LANGGRAPH_POC_DB_USER=poc',
        '-e', 'LANGGRAPH_POC_DB_PASSWORD=poc-only',
        '-e', "GIT_HEAD=$gitHead",
        '-e', "GIT_DIRTY=$gitDirty",
        'maven:3.9.9-eclipse-temurin-21'
    ) + $MavenArgs
    & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven container failed: mvn $($MavenArgs -join ' ')" }
}

try {
    $pgVersionText = & docker run --rm --entrypoint postgres $PostgresImage --version
    if ($LASTEXITCODE -ne 0 -or "$pgVersionText" -notmatch 'PostgreSQL\)\s+(\d+)\.(\d+)') {
        throw "Cannot determine PostgreSQL server version from image $PostgresImage"
    }
    if ([int]$Matches[1] -ne 16 -or [int]$Matches[2] -lt 4) {
        throw "PostgreSQL 16.4+ is required; image reported: $pgVersionText"
    }
    $pgImageId = & docker image inspect $PostgresImage --format '{{.Id}}'
    Write-Host "Using $pgVersionText image=$PostgresImage imageId=$pgImageId"
    Write-Host "PoC source gitHead=$gitHead dirty=$gitDirty"
    & docker run -d --rm --name $pgName -p 127.0.0.1:55432:5432 `
        -e POSTGRES_DB=interviewmirror_poc -e POSTGRES_USER=poc -e POSTGRES_PASSWORD=poc-only `
        $PostgresImage | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the temporary PostgreSQL 16.4 container.' }
    $pgStarted = $true

    $ready = $false
    for ($i = 0; $i -lt 40; $i++) {
        & docker exec $pgName pg_isready -U poc -d interviewmirror_poc 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'Temporary PostgreSQL did not become ready within 80 seconds.' }

    Invoke-MavenInContainer @('mvn', '-q', 'test', 'package')
    Invoke-MavenInContainer @('mvn', '-q', 'exec:java', '-Dexec.args=routing')
    $threadId = "resume-check-$([guid]::NewGuid().ToString('N'))"
    Invoke-MavenInContainer @('mvn', '-q', 'exec:java', "-Dexec.args=crash $threadId")
    # This is a separate Maven/JVM process and a new PostgresSaver instance.
    Invoke-MavenInContainer @('mvn', '-q', 'exec:java', "-Dexec.args=resume $threadId")
    Write-Host 'PASS: tests, conditional routes, checkpoint save, and cross-process resume completed.'
}
finally {
    if ($pgStarted -and -not $KeepPostgres) {
        & docker stop $pgName | Out-Null
    }
}
