# ============================================================================
# push-to-device.ps1 - push the converted HF models to the running emulator
# (or device). The app reads them from its external files dir, which is
# adb-writable without root.
#
#   powershell -ExecutionPolicy Bypass -File tools/models/push-to-device.ps1
# ============================================================================
$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path "$PSScriptRoot\..\.."
$src = Join-Path $repoRoot "models-hf"
$dest = "/sdcard/Android/data/com.onevoice.link/files/models/hf"

if (-not (Test-Path $src)) { throw "models-hf/ not found - run the convert_*.py scripts first." }

# The app must have been launched once so Android creates its files dir.
adb shell mkdir -p $dest

# Bidirectional layout: per-direction ASR/MT/TTS plus the shared espeak data.
foreach ($part in "asr-vi", "asr-en", "mt-vi-en", "mt-en-vi", "tts-vi", "tts-en", "espeak-ng-data") {
    $dir = Join-Path $src $part
    if (Test-Path $dir) {
        Write-Host "Pushing $part ..."
        adb push $dir "$dest/"
    } else {
        Write-Warning "$part missing locally - skipping (app will report it)."
    }
}
adb shell ls $dest
Write-Host "Done. Relaunch the app and tap Initialize Engine (HF backend)."
