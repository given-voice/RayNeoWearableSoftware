# Starts the Android emulator (if not already running), installs the debug app and opens it.
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File simulation\run-emulator.ps1
# Optional: -Avd <name>  (default: the first emulator listed by `emulator -list-avds`)
param([string]$Avd = '')

$ErrorActionPreference = 'Stop'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$emulator = "$sdk\emulator\emulator.exe"
$root = Split-Path $PSScriptRoot -Parent
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }

# adb prints an error (not a value) while no device is up, so don't let that stop the script.
function Test-Booted {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return [bool]((& $adb shell getprop sys.boot_completed 2>$null) -match '1') }
    finally { $ErrorActionPreference = $old }
}

if (-not (& $adb devices | Select-String 'emulator-')) {
    $avds = @(& $emulator -list-avds | Where-Object { $_ })
    if (-not $avds) { throw 'No emulators found. Create one in Android Studio: Tools > Device Manager.' }
    if (-not $Avd) { $Avd = $avds[0] }
    elseif ($avds -notcontains $Avd) { throw "Unknown emulator '$Avd'. Available: $($avds -join ', ')" }

    Write-Host "Starting emulator $Avd (the first start can take a few minutes) ..."
    $emu = Start-Process $emulator -ArgumentList '-avd', $Avd -PassThru
    $deadline = (Get-Date).AddMinutes(5)
    do {
        Start-Sleep -Seconds 2
        if ($emu.HasExited) { throw "The emulator exited right after starting (exit code $($emu.ExitCode))." }
        if ((Get-Date) -gt $deadline) { throw 'Timed out waiting for the emulator to boot.' }
    } until (Test-Booted)
}

Write-Host 'Building and installing the debug app ...'
Push-Location $root
try { & .\gradlew.bat installDebug; if ($LASTEXITCODE -ne 0) { throw 'Build failed' } }
finally { Pop-Location }

# Lets the app reach bridge.py on this PC via the emulator's own 127.0.0.1:5000.
# (The emulator's usual PC address, 10.0.2.2, is blocked for apps on Android 17.)
& $adb reverse tcp:5000 tcp:5000

& $adb shell am start -n com.givenvoice.wearable/.MainActivity
Write-Host 'App started. Now run:  python simulation\bridge.py --keyboard   (or without --keyboard for Wokwi)'
