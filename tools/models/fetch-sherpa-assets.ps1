# ============================================================================
# fetch-sherpa-assets.ps1 - download the two sherpa-onnx binary assets the
# HF emulator pipeline needs. RUN THIS YOURSELF (one-time):
#
#   powershell -ExecutionPolicy Bypass -File tools/models/fetch-sherpa-assets.ps1
#
# 1) sherpa-onnx Android AAR  -> android-app/app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar
#    Official k2-fsa release artifact (Apache-2.0). There is no Maven artifact;
#    the AAR bundles JNI libs for arm64-v8a AND x86_64 (emulator).
# 2) espeak-ng-data           -> models-hf/tts/espeak-ng-data/
#    Phonemizer data Kokoro TTS requires; extracted from the official
#    sherpa-onnx kokoro model tarball.
# ============================================================================
$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path "$PSScriptRoot\..\.."

# static-link variant: ORT is linked into the sherpa JNI lib, avoiding a
# lib/**/libonnxruntime.so collision with the onnxruntime-android Maven dep.
$aarVersion = "1.13.4"
$aarUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$aarVersion/sherpa-onnx-static-link-onnxruntime-$aarVersion.aar"
$aarDest = Join-Path $repoRoot "android-app\app\libs\sherpa-onnx-static-link-onnxruntime-$aarVersion.aar"

$kokoroTarUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"
$hfDir = Join-Path $repoRoot "models-hf"
$ttsEnDir = Join-Path $hfDir "tts-en"
$asrEnDir = Join-Path $hfDir "asr-en"

New-Item -ItemType Directory -Force -Path (Split-Path $aarDest) | Out-Null
New-Item -ItemType Directory -Force -Path $ttsEnDir | Out-Null
New-Item -ItemType Directory -Force -Path $asrEnDir | Out-Null

if (-not (Test-Path $aarDest)) {
    Write-Host "Downloading sherpa-onnx AAR v$aarVersion ..."
    Invoke-WebRequest -Uri $aarUrl -OutFile $aarDest
} else {
    Write-Host "AAR already present: $aarDest"
}

# kokoro-en-v0_19: English TTS (VI->EN output voice) + shared espeak-ng-data.
$espeakDir = Join-Path $hfDir "espeak-ng-data"
if (-not (Test-Path $espeakDir) -or -not (Test-Path (Join-Path $ttsEnDir "model.onnx"))) {
    Write-Host "Downloading kokoro-en tarball (TTS EN + espeak-ng-data) ..."
    $tmp = Join-Path $env:TEMP "kokoro-en-v0_19.tar.bz2"
    Invoke-WebRequest -Uri $kokoroTarUrl -OutFile $tmp
    tar -xjf $tmp -C $env:TEMP
    $ex = Join-Path $env:TEMP "kokoro-en-v0_19"
    foreach ($f in "model.onnx", "voices.bin", "tokens.txt") {
        Copy-Item (Join-Path $ex $f) $ttsEnDir -Force
    }
    if (-not (Test-Path $espeakDir)) {
        Move-Item (Join-Path $ex "espeak-ng-data") $espeakDir
    }
    Remove-Item $tmp -Force
    Remove-Item $ex -Recurse -Force
} else {
    Write-Host "kokoro-en + espeak-ng-data already present."
}

# English ASR: sherpa-ready NeMo Conformer-small (no conversion needed).
if (-not (Test-Path (Join-Path $asrEnDir "model.int8.onnx"))) {
    Write-Host "Downloading English ASR (nemo-ctc-en-conformer-small) ..."
    $base = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-en-conformer-small/resolve/main"
    Invoke-WebRequest -Uri "$base/model.int8.onnx" -OutFile (Join-Path $asrEnDir "model.int8.onnx")
    Invoke-WebRequest -Uri "$base/tokens.txt" -OutFile (Join-Path $asrEnDir "tokens.txt")
} else {
    Write-Host "English ASR already present."
}

Write-Host "Done. Now build the app: cd android-app && .\gradlew :app:assembleDebug"
