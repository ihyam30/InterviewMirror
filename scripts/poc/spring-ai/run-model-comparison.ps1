param(
    [switch]$CompileOnly,
    [switch]$TestOnly,
    [ValidateSet('both', 'qwen', 'glm')][string]$Provider = 'both',
    [ValidateRange(10, 600)][int]$TimeoutSeconds = 60,
    [ValidateSet('default', 'low', 'high', 'max')][string]$ReasoningEffort = 'default',
    [string]$CasesPath = '.\data\poc\model-cases.v1.json',
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,31}$')][string]$RunLabel = ''
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$resolvedCasesPath = if ([System.IO.Path]::IsPathRooted($CasesPath)) {
    (Resolve-Path -LiteralPath $CasesPath).Path
} else {
    (Resolve-Path -LiteralPath (Join-Path $repo $CasesPath)).Path
}
$repoPrefix = $repo.TrimEnd([char[]]@('\', '/')) + [System.IO.Path]::DirectorySeparatorChar
if (-not $resolvedCasesPath.StartsWith($repoPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'CasesPath must resolve to a file inside the repository.'
}
$containerCasesPath = '/workspace/' + $resolvedCasesPath.Substring($repoPrefix.Length).Replace('\', '/')
$gitHead = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot record Git HEAD for the model benchmark.' }
$gitDirty = [bool](& git -C $repo status --porcelain)
Write-Host "Benchmark baseline gitHead=$gitHead dirty=$gitDirty timeoutSeconds=$TimeoutSeconds reasoningEffort=$ReasoningEffort"
Write-Host "datasetPath=$containerCasesPath"

function Invoke-Maven([string[]]$ArgsList) {
    $dockerArgs = @(
        'run', '--rm', '-v', "$repo`:/workspace", '-v', "$repo/scripts/poc/.m2`:/root/.m2",
        '-w', '/workspace/scripts/poc/spring-ai', 'maven:3.9.9-eclipse-temurin-21'
    ) + $ArgsList
    & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) { throw "Spring AI Maven command failed: mvn $($ArgsList -join ' ')" }
}

if ($CompileOnly) {
    Invoke-Maven @('mvn', '-q', 'package', '-DskipTests')
    Write-Host 'PASS: Spring AI benchmark compiled; no provider API was called.'
    exit 0
}

if ($TestOnly) {
    Invoke-Maven @('mvn', '-q', 'test')
    if ($LASTEXITCODE -ne 0) { throw 'Spring AI benchmark tests failed.' }
    Write-Host 'PASS: Spring AI benchmark unit tests; no provider API was called.'
    exit 0
}

$providers = @(
    @{ Name = 'qwen'; Key = $env:DASHSCOPE_API_KEY; Base = 'https://dashscope.aliyuncs.com/compatible-mode/v1'; Model = 'qwen-plus-2025-12-01'; In = $env:QWEN_INPUT_PRICE; Out = $env:QWEN_OUTPUT_PRICE },
    @{ Name = 'glm'; Key = $env:ZHIPUAI_API_KEY; Base = 'https://open.bigmodel.cn/api/paas/v4'; Model = 'glm-5.3-flash'; In = $env:GLM_INPUT_PRICE; Out = $env:GLM_OUTPUT_PRICE }
)
if ($Provider -ne 'both') {
    $providers = @($providers | Where-Object { $_.Name -eq $Provider })
}
if ($ReasoningEffort -ne 'default' -and @($providers | Where-Object { $_.Name -ne 'glm' }).Count -gt 0) {
    throw 'Non-default reasoning effort is currently supported only for the GLM provider.'
}
$missing = @($providers | Where-Object { [string]::IsNullOrWhiteSpace($_.Key) -or [string]::IsNullOrWhiteSpace($_.In) -or [string]::IsNullOrWhiteSpace($_.Out) })
if ($missing.Count -gt 0) {
    $need = ($missing | ForEach-Object { $_.Name }) -join ', '
    $requiredVariables = foreach ($missingProvider in $missing) {
        if ($missingProvider.Name -eq 'qwen') { 'DASHSCOPE_API_KEY', 'QWEN_INPUT_PRICE', 'QWEN_OUTPUT_PRICE' }
        if ($missingProvider.Name -eq 'glm') { 'ZHIPUAI_API_KEY', 'GLM_INPUT_PRICE', 'GLM_OUTPUT_PRICE' }
    }
    throw "Missing local API key and/or verified CNY-per-million input/output prices for: $need. Set $($requiredVariables -join ', '). No values were printed."
}

$resultsDirectory = Join-Path $repo 'data/poc/results/models'
New-Item -ItemType Directory -Path $resultsDirectory -Force | Out-Null
$archiveStamp = Get-Date -Format 'yyyyMMddTHHmmssfff'
$suffixParts = @()
if ($RunLabel) { $suffixParts += $RunLabel }
if ($TimeoutSeconds -ne 60) { $suffixParts += "timeout-$($TimeoutSeconds)s" }
if ($ReasoningEffort -ne 'default') { $suffixParts += "reasoning-$ReasoningEffort" }
$resultSuffix = if ($suffixParts.Count -eq 0) { '' } else { '.' + ($suffixParts -join '.') }
foreach ($providerName in @($providers | ForEach-Object { $_.Name })) {
    $latestResult = Join-Path $resultsDirectory "$providerName$resultSuffix.json"
    if (Test-Path -LiteralPath $latestResult) {
        $archivePath = Join-Path $resultsDirectory "$providerName.$archiveStamp.previous.json"
        Move-Item -LiteralPath $latestResult -Destination $archivePath
        Write-Host "Archived previous $providerName result to $archivePath"
    }
}

foreach ($p in $providers) {
    $output = "/workspace/data/poc/results/models/$($p.Name)$resultSuffix.json"
    $containerArgs = @(
        'run', '--rm', '-v', "$repo`:/workspace", '-v', "$repo/scripts/poc/.m2`:/root/.m2",
        '-w', '/workspace/scripts/poc/spring-ai',
        '-e', "MODEL_PROVIDER=$($p.Name)",
        '-e', "MODEL_BASE_URL=$($p.Base)",
        '-e', "MODEL_ID=$($p.Model)",
        '-e', 'MODEL_API_KEY',
        '-e', "MODEL_PRICE_INPUT_CNY=$($p.In)",
        '-e', "MODEL_PRICE_OUTPUT_CNY=$($p.Out)",
        '-e', "SPRING_AI_OPENAI_TIMEOUT=$($TimeoutSeconds)s",
        '-e', "MODEL_REASONING_EFFORT=$ReasoningEffort",
        '-e', "GIT_HEAD=$gitHead",
        '-e', "GIT_DIRTY=$gitDirty",
        '-e', "MODEL_CASES_PATH=$containerCasesPath",
        '-e', "MODEL_OUTPUT_PATH=$output",
        'maven:3.9.9-eclipse-temurin-21', 'mvn', '-q',
        "-Dspring-boot.run.jvmArguments=-Dspring.ai.openai.timeout=$($TimeoutSeconds)s",
        'spring-boot:run'
    )
    $env:MODEL_API_KEY = $p.Key
    try {
        & docker @containerArgs
        if ($LASTEXITCODE -ne 0) { throw "Provider run failed: $($p.Name)" }
    }
    finally {
        Remove-Item Env:\MODEL_API_KEY -ErrorAction SilentlyContinue
    }
}
Write-Host "Completed provider run(s): $(@($providers | ForEach-Object { $_.Name }) -join ', '); timeoutSeconds=$TimeoutSeconds; reasoningEffort=$ReasoningEffort; datasetPath=$containerCasesPath; resultSuffix='$resultSuffix'"
