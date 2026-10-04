$ErrorActionPreference = 'Stop'

$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$networkName = "interviewmirror-phase3-$suffix"
$postgresName = "interviewmirror-phase3-postgres-$suffix"
$database = 'phase3_checkpoint'
$user = 'phase3_smoke'
$password = 'LocalCheckpointSmokeOnly!'
$cleanupFailures = [System.Collections.Generic.List[string]]::new()

try {
    docker network create $networkName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create an isolated Docker network.' }

    docker run --detach --name $postgresName --network $networkName --network-alias phase3-postgres `
        --env "POSTGRES_DB=$database" --env "POSTGRES_USER=$user" --env "POSTGRES_PASSWORD=$password" `
        --health-cmd "pg_isready -U $user -d $database" --health-interval 2s --health-timeout 2s --health-retries 30 `
        postgres:16-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the isolated PostgreSQL checkpoint database.' }

    $healthy = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        $health = docker inspect --format '{{.State.Health.Status}}' $postgresName 2>$null
        if ($LASTEXITCODE -eq 0 -and $health -eq 'healthy') { $healthy = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $healthy) { throw 'The isolated PostgreSQL checkpoint database did not become healthy.' }

    $backendPath = (Resolve-Path '.\backend').Path
    docker run --rm --network $networkName `
        --env "PHASE3_POSTGRES_URL=jdbc:postgresql://phase3-postgres:5432/$database" `
        --env "PHASE3_POSTGRES_USER=$user" --env "PHASE3_POSTGRES_PASSWORD=$password" `
        --volume "${backendPath}:/workspace" --volume interviewmirror-m2:/root/.m2 `
        --workdir /workspace maven:3.9-eclipse-temurin-21 `
        mvn -B '-Dtest=InterviewPostgresCheckpointRecoveryTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Stage 3 PostgreSQL cross-JVM checkpoint test failed.' }
}
finally {
    docker container inspect $postgresName *> $null
    if ($LASTEXITCODE -eq 0) {
        docker rm --force $postgresName *> $null
        if ($LASTEXITCODE -ne 0) { $cleanupFailures.Add("Could not remove temporary container $postgresName") | Out-Null }
    }
    docker network inspect $networkName *> $null
    if ($LASTEXITCODE -eq 0) {
        docker network rm $networkName *> $null
        if ($LASTEXITCODE -ne 0) { $cleanupFailures.Add("Could not remove temporary network $networkName") | Out-Null }
    }
    if ($cleanupFailures.Count -gt 0) {
        $cleanupFailures | ForEach-Object { Write-Warning $_ }
        if ($LASTEXITCODE -eq 0) { throw 'Temporary checkpoint smoke resources were not fully cleaned up.' }
    }
}
