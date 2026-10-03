param([string]$EnvFile = '.env')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo
$venv = Join-Path $repo 'scripts\poc\.mineru'
$python = Join-Path $venv 'Scripts\python.exe'
$kit = Join-Path $venv 'Scripts\mineru-kit.exe'
$homePath = Join-Path $venv 'home'
$modelPath = Join-Path $venv 'models'

if (-not (Test-Path -LiteralPath $python) -or -not (Test-Path -LiteralPath $kit)) {
    throw 'The pinned MinerU runtime is missing. Follow docs/phase0/MINERU-POC.md to install the local PoC toolchain.'
}
$envPath = Join-Path $repo $EnvFile
if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'Copy .env.example to .env, then set MINERU_WORKER_TOKEN to a unique random 64-character value.'
}
$tokenLine = Get-Content -LiteralPath $envPath | Where-Object { $_ -match '^\s*MINERU_WORKER_TOKEN\s*=' } | Select-Object -Last 1
$token = if ($tokenLine) { ($tokenLine -split '=', 2)[1].Trim().Trim('"').Trim("'") } else { '' }
if ($token.Length -lt 32 -or $token -match '^replace-with-') {
    throw 'Set MINERU_WORKER_TOKEN in .env to a unique random value of at least 32 characters. Do not reuse a provider API key.'
}

$env:MINERU_HOME = $homePath
$env:MINERU_MODEL_SOURCE = 'local'
$env:MINERU_EXECUTABLE = $kit
$env:MINERU_WORKER_TOKEN = $token
$env:MINERU_WORKER_BIND = '127.0.0.1'
$env:MINERU_WORKER_PORT = '8765'
$env:MINERU_TIMEOUT_SECONDS = '120'
$env:UV_CACHE_DIR = Join-Path $repo 'scripts\poc\.uv-cache'
$env:UV_LINK_MODE = 'copy'
$env:PATH = (Join-Path $venv 'Scripts') + [IO.Path]::PathSeparator + $env:PATH
New-Item -ItemType Directory -Force $homePath,$modelPath | Out-Null
# Use the OS temporary directory for per-request files. The virtualenv directory may
# inherit restrictive ACLs on Windows and is not a reliable scratch location.

$configPath = Join-Path $homePath 'config.yaml'
if (-not (Test-Path -LiteralPath $configPath)) {
    $yamlModelPath = $modelPath.Replace('\','/')
    @"
model:
  source: modelscope
  base_dir: '$yamlModelPath'
  small_backend: onnx
  vlm:
    engine: llama-cpp
"@ | Set-Content -LiteralPath $configPath -Encoding utf8
}

& $python -c "import sys; assert sys.version_info[:3] == (3, 12, 14), sys.version"
if ($LASTEXITCODE -ne 0) { throw 'Stage 2 requires the pinned Python 3.12.14 MinerU runtime.' }
& $kit models verify --tier basic --small-backend onnx --vlm-engine auto
if ($LASTEXITCODE -ne 0) { throw 'Local MinerU model verification failed. Install/verify the local models before starting the worker.' }
& $python (Join-Path $PSScriptRoot 'mineru_worker.py')
