$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo
$venv = Join-Path $PSScriptRoot '.mineru'
$python = Join-Path $venv 'Scripts\python.exe'
$env:UV_CACHE_DIR = Join-Path $PSScriptRoot '.uv-cache'
$env:UV_LINK_MODE = 'copy'
if (-not (Test-Path -LiteralPath $python)) {
    uv venv $venv --python 3.12.14
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the pinned Python 3.12.14 virtual environment.' }
}
& $python -c "import sys; assert sys.version_info[:3] == (3, 12, 14), f'Expected Python 3.12.14, found {sys.version}'"
if ($LASTEXITCODE -ne 0) { throw 'scripts/poc/.mineru must use Python 3.12.14; recreate it from the pinned toolchain.' }
uv pip install --python $python setuptools psutil==7.2.2
if ($LASTEXITCODE -ne 0) { throw 'Could not install MinerU build support and RSS measurement dependency.' }
uv pip install --python $python --no-build-isolation mineru==4.0.10
if ($LASTEXITCODE -ne 0) { throw 'MinerU 4.0.10 installation failed.' }
uv pip install --python $python -r (Join-Path $PSScriptRoot 'requirements-generator.lock.txt')
if ($LASTEXITCODE -ne 0) { throw 'Pinned synthetic-corpus generator dependencies could not be installed.' }
& $python -c "import sys, importlib.metadata as m; print('Python', sys.version); print('MinerU', m.version('mineru'), 'psutil', m.version('psutil')); [print(n, m.version(n)) for n in ('python-docx','reportlab','Pillow','lxml','typing-extensions','charset-normalizer')]"
& $python (Join-Path $PSScriptRoot 'generate_samples.py')
if ($LASTEXITCODE -ne 0) { throw 'Pinned synthetic corpus generation failed.' }
& $python (Join-Path $PSScriptRoot 'verify_dataset.py')
if ($LASTEXITCODE -ne 0) { throw 'Pinned PoC toolchain verification failed.' }
