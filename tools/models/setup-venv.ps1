# ============================================================================
# setup-venv.ps1 - create the conversion venv ON DRIVE D: (C: is nearly full)
# and install the export toolchain.
#
#   powershell -ExecutionPolicy Bypass -File tools/models/setup-venv.ps1
#   powershell -ExecutionPolicy Bypass -File tools/models/setup-venv.ps1 -WithNemo
#
# -WithNemo additionally installs nemo_toolkit[asr] (several GB) needed only
# for convert_parakeet.py.
# ============================================================================
param([switch]$WithNemo)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$venv = Join-Path $here ".venv"

# Keep pip's download cache off the almost-full C: drive.
$env:PIP_CACHE_DIR = Join-Path $here ".pip-cache"

if (-not (Test-Path $venv)) {
    Write-Host "Creating venv at $venv ..."
    python -m venv $venv
}
$py = Join-Path $venv "Scripts\python.exe"

& $py -m pip install --upgrade pip
& $py -m pip install "optimum[onnxruntime]" "transformers" "torch" "onnx" "sentencepiece" "numpy"

if ($WithNemo) {
    Write-Host "Installing NeMo (this is several GB)..."
    & $py -m pip install "nemo_toolkit[asr]"
}

Write-Host "venv ready: $py"
