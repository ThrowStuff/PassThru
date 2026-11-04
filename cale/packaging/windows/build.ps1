# SPDX-License-Identifier: GPL-3.0-only
param([string]$Wheelhouse)
$ErrorActionPreference = 'Stop'
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw "Command failed: $LASTEXITCODE" } }
Push-Location $PSScriptRoot
try {
    # Find Python 3.12: py launcher, then standard per-user/system installs,
    # then python on PATH (skipping the Microsoft Store alias stub).
    $base = $null
    if (Get-Command py -ErrorAction SilentlyContinue) { $base = @('py', '-3.12') }
    if (-not $base) {
        $candidates = @("$env:LOCALAPPDATA\Programs\Python\Python312\python.exe",
                        "$env:ProgramFiles\Python312\python.exe")
        $found = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
        if ($found) { $base = @($found) }
    }
    if (-not $base) {
        $cmd = Get-Command python -ErrorAction SilentlyContinue
        if ($cmd -and $cmd.Source -notlike '*WindowsApps*') { $base = @($cmd.Source) }
    }
    if (-not $base) { throw 'Python 3.12 not found. Install it from python.org or: winget install Python.Python.3.12' }
    $exe = $base[0]; $rest = @($base | Select-Object -Skip 1)
    & $exe @rest -m venv .venv
    Check-Exit
    $python = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
    $pipArgs = @('-m', 'pip', 'install', '-r', 'requirements.txt', '--only-binary=:all:')
    if ($Wheelhouse) { $pipArgs += @('--no-index', '--find-links', $Wheelhouse) }
    & $python @pipArgs
    Check-Exit
    & $python -m PyInstaller --noconfirm --clean cale.spec
    Check-Exit
    Write-Host 'Built dist\PassThru Cale. Compile installer.iss with Inno Setup 6 next.'
} finally { Pop-Location }
