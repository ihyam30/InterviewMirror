param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$MinioUrl = 'http://127.0.0.1:9000',
    [string]$Bucket = 'interviewmirror-private',
    [string]$Demo1Password = 'MirrorDemo1!',
    [string]$Demo2Password = 'MirrorDemo2!'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

function New-LoginSession([string]$Username, [string]$Password) {
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.CookieContainer = [System.Net.CookieContainer]::new()
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.BaseAddress = [Uri]$BaseUrl
    $client.DefaultRequestHeaders.Accept.ParseAdd('application/json')

    $csrfResponse = $client.GetAsync('/api/v1/auth/csrf').GetAwaiter().GetResult()
    if (-not $csrfResponse.IsSuccessStatusCode) { throw "CSRF initialization failed for $($Username): $([int]$csrfResponse.StatusCode)" }
    $csrfPayload = $csrfResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    $client.DefaultRequestHeaders.Add('X-XSRF-TOKEN', [string]$csrfPayload.data.token)
    $loginJson = @{ identifier = $Username; password = $Password } | ConvertTo-Json -Compress
    $loginContent = [System.Net.Http.StringContent]::new($loginJson, [System.Text.Encoding]::UTF8, 'application/json')
    $loginResponse = $client.PostAsync('/api/v1/auth/login', $loginContent).GetAwaiter().GetResult()
    if (-not $loginResponse.IsSuccessStatusCode) { throw "Login failed for $($Username): $([int]$loginResponse.StatusCode)" }

    $client.DefaultRequestHeaders.Remove('X-XSRF-TOKEN') | Out-Null
    $csrfResponse = $client.GetAsync('/api/v1/auth/csrf').GetAwaiter().GetResult()
    $csrfPayload = $csrfResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    $client.DefaultRequestHeaders.Add('X-XSRF-TOKEN', [string]$csrfPayload.data.token)
    $meResponse = $client.GetAsync('/api/v1/auth/me').GetAwaiter().GetResult()
    if (-not $meResponse.IsSuccessStatusCode) { throw "Current user request failed for $Username" }
    $me = $meResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    return [pscustomobject]@{ Client = $client; User = $me.data }
}

function Send-Api($Session, [string]$Method, [string]$Path, [string]$Body = $null, [System.Net.Http.HttpContent]$Content = $null) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Path)
    if ($null -ne $Content) { $request.Content = $Content }
    elseif ($null -ne $Body) { $request.Content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, 'application/json') }
    $response = $Session.Client.SendAsync($request).GetAwaiter().GetResult()
    $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $responseBody; Response = $response }
}

function Assert-Status($Result, [int]$Expected, [string]$Scenario) {
    if ($Result.Status -ne $Expected) { throw "$Scenario expected HTTP $Expected, received HTTP $($Result.Status): $($Result.Body)" }
    Write-Host "PASS $Scenario (HTTP $Expected)"
}

function Assert-CleanedResource($Session, [string]$Id) {
    if ([string]::IsNullOrWhiteSpace($Id)) { return }
    $deleted = Send-Api $Session 'DELETE' "/api/v1/resources/$Id"
    if ($deleted.Status -notin @(200, 404)) { throw "Cleanup resource $Id failed with HTTP $($deleted.Status): $($deleted.Body)" }
    $read = Send-Api $Session 'GET' "/api/v1/resources/$Id"
    if ($read.Status -ne 404) { throw "Resource $Id remained readable after cleanup (HTTP $($read.Status))." }
    Write-Host "PASS cleanup verified for resource $Id (HTTP 404 after deletion)"
}

function Assert-CleanedFile($Session, [string]$Id) {
    if ([string]::IsNullOrWhiteSpace($Id)) { return }
    $deleted = Send-Api $Session 'DELETE' "/api/v1/files/$Id"
    if ($deleted.Status -notin @(200, 404)) { throw "Cleanup file $Id failed with HTTP $($deleted.Status): $($deleted.Body)" }
    foreach ($path in @("/api/v1/files/$Id", "/api/v1/files/$Id/content")) {
        $read = Send-Api $Session 'GET' $path
        if ($read.Status -ne 404) { throw "File $Id remained accessible at $path after cleanup (HTTP $($read.Status))." }
    }
    Write-Host "PASS cleanup verified for file $Id (metadata and content return HTTP 404)"
}

$a = $null
$b = $null
$resourceId = $null
$fileId = $null
$failed = $false
$cleanupErrors = [System.Collections.Generic.List[string]]::new()
$runId = [Guid]::NewGuid().ToString('N')
$resourceMarker = "phase1-isolation-smoke-$runId"
$fileMarker = "phase1-smoke-$runId.txt"
try {
    $a = New-LoginSession 'demo1' $Demo1Password
    $b = New-LoginSession 'demo2' $Demo2Password
    if ($a.User.id -eq $b.User.id) { throw 'Demo accounts unexpectedly share the same user ID.' }
    Write-Host 'PASS demo1 and demo2 authenticate as distinct users'

    $resourceBody = @{ resourceType = 'NOTE'; title = $resourceMarker; content = 'owner A' } | ConvertTo-Json -Compress
    $created = Send-Api $a 'POST' '/api/v1/resources' $resourceBody
    Assert-Status $created 200 'A creates private resource'
    $resourceId = (($created.Body | ConvertFrom-Json).data.id)
    $otherResources = (Send-Api $b 'GET' '/api/v1/resources')
    Assert-Status $otherResources 200 'B lists own resources'
    $otherRows = ($otherResources.Body | ConvertFrom-Json).data
    if (@($otherRows | Where-Object id -eq $resourceId).Count -gt 0) { throw 'B resource list leaked A resource.' }
    Assert-Status (Send-Api $b 'GET' "/api/v1/resources/$resourceId") 404 'B reads A resource'
    $updateBody = @{ title = 'hijacked'; content = 'owner B' } | ConvertTo-Json -Compress
    Assert-Status (Send-Api $b 'PUT' "/api/v1/resources/$resourceId" $updateBody) 404 'B updates A resource'
    Assert-Status (Send-Api $b 'DELETE' "/api/v1/resources/$resourceId") 404 'B deletes A resource'
    Assert-Status (Send-Api $a 'GET' "/api/v1/resources/$resourceId") 200 'A retains access to own resource'

    $badMultipart = [System.Net.Http.MultipartFormDataContent]::new()
    $badPart = [System.Net.Http.ByteArrayContent]::new([System.Text.Encoding]::ASCII.GetBytes('MZ executable'))
    $badPart.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/x-msdownload')
    $badMultipart.Add($badPart, 'file', "phase1-smoke-$runId.exe")
    try { Assert-Status (Send-Api $a 'POST' '/api/v1/files' $null $badMultipart) 400 'Server rejects executable upload' }
    finally { $badMultipart.Dispose() }

    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes('private file isolation smoke data')
    $filePart = [System.Net.Http.ByteArrayContent]::new($bytes)
    $filePart.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('text/plain')
    $multipart.Add($filePart, 'file', $fileMarker)
    try { $uploaded = Send-Api $a 'POST' '/api/v1/files' $null $multipart }
    finally { $multipart.Dispose() }
    Assert-Status $uploaded 200 'A uploads a private file'
    $fileView = ($uploaded.Body | ConvertFrom-Json).data
    $fileId = $fileView.id
    if ($fileView.PSObject.Properties.Name -contains 'objectKey' -or $fileView.PSObject.Properties.Name -contains 'ownerId') {
        throw 'File API leaked internal object key or owner ID.'
    }
    Assert-Status (Send-Api $b 'GET' "/api/v1/files/$fileId") 404 'B reads A file metadata'
    Assert-Status (Send-Api $b 'GET' "/api/v1/files/$fileId/content") 404 'B downloads A file'
    Assert-Status (Send-Api $b 'GET' "/api/v1/files/$fileId/presigned-url") 404 'B cannot obtain a presigned URL (endpoint is intentionally not exposed)'
    Assert-Status (Send-Api $b 'DELETE' "/api/v1/files/$fileId") 404 'B deletes A file'

    $knownObjectPath = "$MinioUrl/$Bucket/users/$($a.User.id)/$fileId"
    try {
        $direct = Invoke-WebRequest -Uri $knownObjectPath -Method Get -SkipHttpErrorCheck
        if ([int]$direct.StatusCode -notin @(403, 404)) { throw "Anonymous object request expected 403/404, received HTTP $($direct.StatusCode)." }
        Write-Host "PASS unauthenticated access using guessed object key (HTTP $($direct.StatusCode))"
    } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -in @(403, 404)) {
            Write-Host "PASS unauthenticated access using guessed object key (HTTP $([int]$_.Exception.Response.StatusCode))"
        } else { throw }
    }

    $downloaded = Send-Api $a 'GET' "/api/v1/files/$fileId/content"
    Assert-Status $downloaded 200 'A downloads own file'
    if ($downloaded.Body -ne 'private file isolation smoke data') { throw 'Downloaded content did not match the uploaded file.' }
    Assert-Status (Send-Api $a 'DELETE' "/api/v1/files/$fileId") 200 'A deletes own file'
    Assert-Status (Send-Api $a 'GET' "/api/v1/files/$fileId") 404 'A cannot read deleted file metadata'
    Assert-Status (Send-Api $a 'DELETE' "/api/v1/resources/$resourceId") 200 'A deletes own resource'
    Assert-Status (Send-Api $a 'GET' "/api/v1/resources/$resourceId") 404 'A cannot read deleted resource'

    Write-Host 'All local account and file isolation checks passed.'
} catch {
    $failed = $true
    throw
} finally {
    if ($null -ne $a) {
        if ([string]::IsNullOrWhiteSpace($fileId)) {
            try {
                $ownedFiles = Send-Api $a 'GET' '/api/v1/files'
                if ($ownedFiles.Status -ne 200) { throw "Could not list owned files during cleanup (HTTP $($ownedFiles.Status))." }
                $match = @(($ownedFiles.Body | ConvertFrom-Json).data | Where-Object originalFilename -eq $fileMarker | Select-Object -First 1)
                if ($match.Count -gt 0) { $fileId = [string]$match[0].id }
            } catch { $cleanupErrors.Add($_.Exception.Message) | Out-Null }
        }
        if ([string]::IsNullOrWhiteSpace($resourceId)) {
            try {
                $ownedResources = Send-Api $a 'GET' '/api/v1/resources'
                if ($ownedResources.Status -ne 200) { throw "Could not list owned resources during cleanup (HTTP $($ownedResources.Status))." }
                $match = @(($ownedResources.Body | ConvertFrom-Json).data | Where-Object title -eq $resourceMarker | Select-Object -First 1)
                if ($match.Count -gt 0) { $resourceId = [string]$match[0].id }
            } catch { $cleanupErrors.Add($_.Exception.Message) | Out-Null }
        }
        try { Assert-CleanedFile $a $fileId } catch { $cleanupErrors.Add($_.Exception.Message) | Out-Null }
        try { Assert-CleanedResource $a $resourceId } catch { $cleanupErrors.Add($_.Exception.Message) | Out-Null }
        $a.Client.Dispose()
    }
    if ($null -ne $b) { $b.Client.Dispose() }
    if ($cleanupErrors.Count -gt 0) {
        $message = "Smoke-test cleanup failed: $($cleanupErrors -join '; ')"
        if ($failed) { Write-Warning $message } else { throw $message }
    }
}
