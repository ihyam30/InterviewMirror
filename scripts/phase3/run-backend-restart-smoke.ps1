$ErrorActionPreference = 'Stop'

# Uses unique, disposable containers and a network plus the existing Maven dependency cache.
# It never attaches to the normal Compose project or mounts its database/storage volumes.
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$networkName = "interviewmirror-phase3-restart-$suffix"
$postgresName = "interviewmirror-phase3-restart-postgres-$suffix"
$minioName = "interviewmirror-phase3-restart-minio-$suffix"
$mavenName = "interviewmirror-phase3-restart-maven-$suffix"
$mavenVolume = 'interviewmirror-m2'
$database = 'phase3_backend_restart'
$databaseUser = 'phase3_restart_smoke'
$databasePassword = 'LocalRestartSmokeOnly!'
$minioUser = 'phase3-smoke-local'
$minioPassword = 'LocalRestartSmokeStorageOnly!'
$minioBucket = 'phase3-restart-smoke'
$minioImage = 'interviewmirror/minio-local:RELEASE.2025-10-15T17-29-55Z'
$mavenImage = 'maven:3.9.11-eclipse-temurin-21'
$cleanupFailures = [System.Collections.Generic.List[string]]::new()

function Remove-DockerResource([string] $kind, [string] $name) {
    docker $kind inspect $name *> $null
    if ($LASTEXITCODE -eq 0) {
        if ($kind -eq 'container') { docker rm --force $name *> $null }
        elseif ($kind -eq 'volume') { docker volume rm $name *> $null }
        elseif ($kind -eq 'network') { docker network rm $name *> $null }
        if ($LASTEXITCODE -ne 0) { $cleanupFailures.Add("Could not remove temporary $kind $name") | Out-Null }
    }
}

try {
    docker image inspect $minioImage *> $null
    if ($LASTEXITCODE -ne 0) { throw "Required locally built MinIO image is missing: $minioImage. Run docker compose build minio first." }
    docker image inspect $mavenImage *> $null
    if ($LASTEXITCODE -ne 0) { throw "Required Maven test image is missing: $mavenImage." }

    docker network create $networkName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create an isolated restart smoke network.' }
    docker run --detach --name $postgresName --network $networkName --network-alias phase3-postgres `
        --env "POSTGRES_DB=$database" --env "POSTGRES_USER=$databaseUser" --env "POSTGRES_PASSWORD=$databasePassword" `
        --health-cmd "pg_isready -U $databaseUser -d $database" --health-interval 2s --health-timeout 2s --health-retries 30 `
        postgres:17.6-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated PostgreSQL.' }

    docker run --detach --name $minioName --network $networkName --network-alias phase3-minio `
        --env "MINIO_ROOT_USER=$minioUser" --env "MINIO_ROOT_PASSWORD=$minioPassword" `
        $minioImage server /data --console-address :9001 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated MinIO.' }

    foreach ($containerName in @($postgresName, $minioName)) {
        $healthy = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            $state = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $containerName 2>$null
            if ($LASTEXITCODE -eq 0 -and $state -eq 'healthy') { $healthy = $true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $healthy) { throw "Temporary service did not become healthy: $containerName" }
    }

    $backendPath = (Resolve-Path '.\backend').Path
    docker run --rm --name $mavenName --network $networkName `
        --env "PHASE3_POSTGRES_URL=jdbc:postgresql://phase3-postgres:5432/$database" `
        --env "PHASE3_POSTGRES_USER=$databaseUser" --env "PHASE3_POSTGRES_PASSWORD=$databasePassword" `
        --env "PHASE3_BACKEND_POSTGRES_URL=jdbc:postgresql://phase3-postgres:5432/$database" `
        --env "PHASE3_BACKEND_POSTGRES_USER=$databaseUser" --env "PHASE3_BACKEND_POSTGRES_PASSWORD=$databasePassword" `
        --env 'PHASE3_BACKEND_S3_ENDPOINT=http://phase3-minio:9000' `
        --env "PHASE3_BACKEND_S3_ACCESS_KEY=$minioUser" --env "PHASE3_BACKEND_S3_SECRET_KEY=$minioPassword" `
        --env "PHASE3_BACKEND_S3_BUCKET=$minioBucket" `
        --volume "${backendPath}:/workspace" --volume "${mavenVolume}:/root/.m2" `
        --workdir /workspace $mavenImage `
        mvn -B '-Dtest=InterviewPostgresCheckpointRecoveryTest,InterviewBackendRestartRecoveryTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Stage 3 PostgreSQL checkpoint and full backend restart recovery smoke failed.' }
}
finally {
    Remove-DockerResource 'container' $mavenName
    Remove-DockerResource 'container' $minioName
    Remove-DockerResource 'container' $postgresName
    Remove-DockerResource 'network' $networkName
    if ($cleanupFailures.Count -gt 0) {
        $cleanupFailures | ForEach-Object { Write-Warning $_ }
        throw 'Temporary restart smoke resources were not fully cleaned up.'
    }
}
