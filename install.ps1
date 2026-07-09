# Windows Installer for Android Mic Bridge (miccpy)
$ErrorActionPreference = "Stop"

# Configuration
$InstallDir = "$HOME\.miccpy"
$RepoUrl = "https://raw.githubusercontent.com/typedbywill/amb/main"
$ReleasesPage = "https://github.com/typedbywill/amb/releases"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "   Installing Android Mic Bridge (miccpy)  " -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Check Python installation
Write-Host "[1/6] Checking for Python..." -ForegroundColor Yellow
if (!(Get-Command python -ErrorAction SilentlyContinue)) {
    Write-Host "Python not found! Attempting to install via winget..." -ForegroundColor Cyan
    try {
        winget install Python.Python.3.12 --accept-source-agreements --accept-package-agreements
        # Refresh env path for the current process
        $env:PATH = [Environment]::GetEnvironmentVariable("PATH", "Machine") + ";" + [Environment]::GetEnvironmentVariable("PATH", "User")
    } catch {
        Write-Host "Automatic Python installation failed. Please install Python 3.12+ manually from https://python.org and run this script again." -ForegroundColor Red
        Exit 1
    }
} else {
    $pythonVersion = & python --version
    Write-Host "Found Python: $pythonVersion" -ForegroundColor Green
}

# 2. Create installation directory structure
Write-Host "[2/6] Creating installation directory at $InstallDir..." -ForegroundColor Yellow
if (!(Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Path $InstallDir | Out-Null
}
if (!(Test-Path "$InstallDir\desktop")) {
    New-Item -ItemType Directory -Path "$InstallDir\desktop" | Out-Null
}

# 3. Download Client Scripts
Write-Host "[3/6] Downloading miccpy client scripts..." -ForegroundColor Yellow
try {
    Write-Host "Downloading miccpy.py..." -ForegroundColor Gray
    Invoke-WebRequest -Uri "$RepoUrl/desktop/miccpy.py" -OutFile "$InstallDir\desktop\miccpy.py" -UseBasicParsing
    
    Write-Host "Downloading miccpy.bat..." -ForegroundColor Gray
    Invoke-WebRequest -Uri "$RepoUrl/miccpy.bat" -OutFile "$InstallDir\miccpy.bat" -UseBasicParsing
    
    Write-Host "Client scripts downloaded successfully." -ForegroundColor Green
} catch {
    Write-Host "Failed to download client scripts: $_" -ForegroundColor Red
    Exit 1
}

# 4. Check/Install ADB (Android Debug Bridge)
Write-Host "[4/6] Checking for ADB..." -ForegroundColor Yellow
$adbPath = Get-Command adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (!$adbPath) {
    if (Test-Path "$InstallDir\adb.exe") {
        Write-Host "Found local ADB in installation directory." -ForegroundColor Green
    } else {
        Write-Host "ADB not found. Downloading Google Platform-Tools (ADB only)..." -ForegroundColor Cyan
        try {
            $adbZip = "$InstallDir\platform-tools.zip"
            Invoke-WebRequest -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $adbZip -UseBasicParsing
            
            Write-Host "Extracting ADB..." -ForegroundColor Gray
            Expand-Archive -Path $adbZip -DestinationPath "$InstallDir\temp" -Force
            
            Move-Item "$InstallDir\temp\platform-tools\adb.exe" "$InstallDir\" -Force
            Move-Item "$InstallDir\temp\platform-tools\AdbWinApi.dll" "$InstallDir\" -Force
            Move-Item "$InstallDir\temp\platform-tools\AdbWinUsbApi.dll" "$InstallDir\" -Force
            
            # Cleanup
            Remove-Item "$InstallDir\temp" -Recurse -Force
            Remove-Item $adbZip -Force
            
            Write-Host "ADB installed successfully inside $InstallDir." -ForegroundColor Green
        } catch {
            Write-Host "Failed to download and set up ADB: $_" -ForegroundColor Yellow
            Write-Host "You might need to install Android SDK Platform-Tools manually." -ForegroundColor Yellow
        }
    }
} else {
    Write-Host "Found system ADB at: $adbPath" -ForegroundColor Green
}

# 5. Install Python dependencies
Write-Host "[5/6] Installing Python package dependencies (sounddevice, pyogg, numpy)..." -ForegroundColor Yellow
try {
    & python -m pip install --upgrade pip
    & python -m pip install sounddevice pyogg numpy
    Write-Host "Python packages installed successfully." -ForegroundColor Green
} catch {
    Write-Host "Warning: Some python packages failed to install. Please run 'pip install sounddevice pyogg numpy' manually." -ForegroundColor Yellow
}

# Try to fetch the Android app APK from releases
try {
    Write-Host "Attempting to download latest miccpy Android APK..." -ForegroundColor Yellow
    # Note: We will attempt to get it from GitHub releases. If they haven't uploaded it yet, we print a warning.
    $apkUrl = "https://github.com/typedbywill/amb/releases/latest/download/app-debug.apk"
    Invoke-WebRequest -Uri $apkUrl -OutFile "$InstallDir\miccpy.apk" -UseBasicParsing
    Write-Host "Android APK downloaded to: $InstallDir\miccpy.apk" -ForegroundColor Green
    Write-Host "You can install it on your device using: adb install $InstallDir\miccpy.apk" -ForegroundColor Green
} catch {
    Write-Host "Could not automatically download the APK (no release published yet or network error)." -ForegroundColor Yellow
    Write-Host "To install the Android app, please build it using './gradlew installDebug' in the android/ directory, or download the APK manually from: $ReleasesPage" -ForegroundColor Cyan
}

# 6. Add installation directory to User PATH
Write-Host "[6/6] Configuring PATH..." -ForegroundColor Yellow
$userPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($userPath -notlike "*$InstallDir*") {
    $newPath = "$userPath;$InstallDir"
    # Ensure we don't have double semicolons
    $newPath = $newPath -replace ";+", ";"
    [Environment]::SetEnvironmentVariable("PATH", $newPath, "User")
    $env:PATH += ";$InstallDir"
    Write-Host "Added $InstallDir to user PATH." -ForegroundColor Green
    Write-Host "NOTE: Please restart your terminal/command prompt to apply PATH changes." -ForegroundColor Cyan
} else {
    Write-Host "$InstallDir is already in user PATH." -ForegroundColor Green
}

Write-Host "=========================================" -ForegroundColor Green
Write-Host "  Android Mic Bridge installed successfully! " -ForegroundColor Green
Write-Host "  Run: miccpy" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green
