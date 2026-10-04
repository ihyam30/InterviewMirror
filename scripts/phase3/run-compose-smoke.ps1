$ErrorActionPreference = 'Stop'

# Builds and verifies an isolated full Compose stack using only example local credentials.
# Unique ports, project name and volumes ensure the developer's active stack is untouched.
$project = 'interviewmirror-phase3-smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$basePort = 0
for ($try = 0; $try -lt 100; $try++) {
    $candidate = Get-Random -Minimum 32000 -Maximum 52000
    $free = $true
    for ($offset = 0; $offset -lt 5; $offset++) {
        $listener = $null
        try {
            $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, ($candidate + $offset))
            $listener.Start()
        } catch {
            $free = $false
        } finally {
            if ($listener) { $listener.Stop() }
        }
        if (-not $free) { break }
    }
    if ($free) { $basePort = $candidate; break }
}
if ($basePort -eq 0) { throw 'Could not allocate five free local ports for the isolated Compose smoke.' }

$managedEnvironmentVariables = @(
    'DATABASE_NAME', 'DATABASE_USER', 'DATABASE_PASSWORD', 'DATABASE_PORT',
    'MINIO_ROOT_USER', 'MINIO_ROOT_PASSWORD', 'MINIO_API_PORT', 'MINIO_CONSOLE_PORT', 'S3_BUCKET',
    'BACKEND_PORT', 'FRONTEND_PORT', 'DEMO1_PASSWORD', 'DEMO2_PASSWORD', 'INTERVIEW_MODEL_ENABLED',
    'INTERVIEW_MODEL_API_KEY', 'MINERU_WORKER_TOKEN'
)
$previousEnvironment = @{}
foreach ($name in $managedEnvironmentVariables) {
    $item = Get-Item "Env:$name" -ErrorAction SilentlyContinue
    $previousEnvironment[$name] = if ($null -eq $item) {
        @{ Exists = $false; Value = $null }
    } else {
        @{ Exists = $true; Value = $item.Value }
    }
}

$env:DATABASE_NAME = 'interviewmirror'
$env:DATABASE_USER = 'interviewmirror'
$env:DATABASE_PASSWORD = 'local-only-change-this-db-password'
$env:DATABASE_PORT = [string]$basePort
$env:MINIO_ROOT_USER = 'interviewmirror-local'
$env:MINIO_ROOT_PASSWORD = 'local-only-change-this-storage-password'
$env:MINIO_API_PORT = [string]($basePort + 1)
$env:MINIO_CONSOLE_PORT = [string]($basePort + 2)
$env:S3_BUCKET = 'interviewmirror-private'
$env:BACKEND_PORT = [string]($basePort + 3)
$env:FRONTEND_PORT = [string]($basePort + 4)
$env:DEMO1_PASSWORD = 'MirrorDemo1!'
$env:DEMO2_PASSWORD = 'MirrorDemo2!'
$env:INTERVIEW_MODEL_ENABLED = 'false'
$env:INTERVIEW_MODEL_API_KEY = 'disabled'
$env:MINERU_WORKER_TOKEN = ''
$compose = @('--project-name', $project, '--env-file', '.env.example')
$failed = $false

try {
    docker compose @compose build
    if ($LASTEXITCODE -ne 0) { throw 'Isolated Compose image build failed.' }

    docker compose @compose up --detach
    if ($LASTEXITCODE -ne 0) { throw 'Isolated Compose services failed to start.' }

    $services = @('postgres', 'minio', 'backend', 'frontend')
    $deadline = (Get-Date).AddMinutes(4)
    do {
        $allHealthy = $true
        foreach ($service in $services) {
            $id = docker compose @compose ps --quiet $service
            if ($LASTEXITCODE -ne 0 -or -not $id) { $allHealthy = $false; break }
            $health = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $id
            if ($LASTEXITCODE -ne 0 -or $health -ne 'healthy') { $allHealthy = $false; break }
        }
        if ($allHealthy) { break }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)

    if (-not $allHealthy) { throw 'One or more isolated Compose services did not become healthy.' }

    $backendUrl = "http://127.0.0.1:$($basePort + 3)"
    $frontendUrl = "http://127.0.0.1:$($basePort + 4)"
    $healthResponse = Invoke-RestMethod "$backendUrl/actuator/health"
    if ($healthResponse.status -ne 'UP') { throw 'Backend actuator health is not UP.' }

    $frontendResponse = Invoke-WebRequest $frontendUrl
    if ($frontendResponse.StatusCode -ne 200) { throw 'Frontend did not return HTTP 200.' }

    $postgresId = docker compose @compose ps --quiet postgres
    $versions = docker exec $postgresId psql -U $env:DATABASE_USER -d $env:DATABASE_NAME -Atc `
        "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success"
    if ($LASTEXITCODE -ne 0 -or $versions -notmatch '1,2,3,4') {
        throw "Expected Flyway V1-V4 to be applied; received: $versions"
    }

    $csrf = Invoke-RestMethod "$backendUrl/api/v1/auth/csrf" -SessionVariable session
    $headers = @{ 'X-XSRF-TOKEN' = $csrf.data.token }
    $login = Invoke-RestMethod "$backendUrl/api/v1/auth/login" -Method Post -WebSession $session `
        -Headers $headers -ContentType 'application/json' `
        -Body ('{"identifier":"demo1","password":"' + $env:DEMO1_PASSWORD + '"}')
    if ($login.data.username -ne 'demo1') { throw 'Demo login returned an unexpected account.' }

    Write-Output "ISOLATED_COMPOSE_PASS project=$project ports=$basePort-$($basePort + 4) health=4/4 migrations=$versions frontend=200 login=demo1"
} catch {
    $failed = $true
    Write-Error $_
    docker compose @compose ps
    docker compose @compose logs --no-color --tail 100
} finally {
    docker compose @compose down --volumes --remove-orphans
    if ($LASTEXITCODE -ne 0) {
        $failed = $true
        Write-Warning "Could not fully remove isolated Compose project $project"
    }
    foreach ($name in $managedEnvironmentVariables) {
        $previous = $previousEnvironment[$name]
        if ($previous.Exists) {
            Set-Item "Env:$name" $previous.Value
        } else {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        }
    }
}

if ($failed) { exit 1 }
