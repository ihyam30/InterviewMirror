param([ValidateSet('basic','standard')][string]$Tier = 'basic', [int]$TimeoutSeconds = 600)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo
$venv = Join-Path $PSScriptRoot '.mineru'
$python = Join-Path $venv 'Scripts\python.exe'
$kit = Join-Path $venv 'Scripts\mineru-kit.exe'
if (-not (Test-Path -LiteralPath $python) -or -not (Test-Path -LiteralPath $kit)) {
    throw 'Create scripts/poc/.mineru with the pinned Python 3.12.14 toolchain, then install MinerU and generator dependencies.'
}
$homePath = Join-Path $venv 'home'
$modelPath = Join-Path $venv 'models'
New-Item -ItemType Directory -Force $homePath,$modelPath | Out-Null
$env:MINERU_HOME = $homePath
$env:MINERU_MODEL_SOURCE = 'local'
$env:UV_CACHE_DIR = Join-Path $PSScriptRoot '.uv-cache'
$env:UV_LINK_MODE = 'copy'
& $python -c "from pathlib import Path; import sys; sys.path.insert(0, str(Path('scripts/poc').resolve())); from poc_runtime import check_runtime; check_runtime()"
if ($LASTEXITCODE -ne 0) { throw 'Pinned PoC Python runtime or generator packages are unavailable.' }
& $python (Join-Path $PSScriptRoot 'generate_samples.py')
if ($LASTEXITCODE -ne 0) { throw 'Synthetic sample generation failed.' }
& $python (Join-Path $PSScriptRoot 'verify_dataset.py')
if ($LASTEXITCODE -ne 0) { throw 'Synthetic corpus integrity/coverage check failed.' }
$yamlModelPath = $modelPath.Replace('\','/')
@"
model:
  source: modelscope
  base_dir: '$yamlModelPath'
  small_backend: onnx
  vlm:
    engine: llama-cpp
"@ | Set-Content -LiteralPath (Join-Path $homePath 'config.yaml') -Encoding utf8

$smallBackend = 'onnx'
$engine = if ($Tier -eq 'standard') { 'llama-cpp' } else { 'auto' }
& $kit models verify --tier $Tier --small-backend $smallBackend --vlm-engine $engine
if ($LASTEXITCODE -ne 0) {
    $env:MINERU_MODEL_SOURCE = 'modelscope'
    & $kit models download --tier $Tier --small-backend $smallBackend --vlm-engine $engine --source modelscope
    if ($LASTEXITCODE -ne 0) { throw 'Local MinerU model download failed; no remote document parsing was attempted.' }
    $env:MINERU_MODEL_SOURCE = 'local'
    & $kit models verify --tier $Tier --small-backend $smallBackend --vlm-engine $engine
    if ($LASTEXITCODE -ne 0) { throw 'Local MinerU model verification failed.' }
}
$env:MINERU_MODEL_SOURCE = 'local'
& $python (Join-Path $PSScriptRoot 'run_mineru.py') --mineru $kit --tier $Tier --timeout $TimeoutSeconds
$parseExit = $LASTEXITCODE
if ($parseExit -ne 0) { throw "Local MinerU corpus parse failed; inspect data/poc/results/mineru/run-manifest.json" }
& $python (Join-Path $PSScriptRoot 'score_parser_outputs.py')
if ($LASTEXITCODE -ne 0) { throw 'MinerU evaluation failed one or more extraction gates.' }
