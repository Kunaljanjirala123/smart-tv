# TvCommon.ps1 - shared by the Windows scripts: finds adb and connects to the Fire TV.
# Dot-source it: . "$PSScriptRoot\TvCommon.ps1"

function Say($m)  { Write-Host "`n== $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "`n  $m" -ForegroundColor Red; Read-Host 'Press Enter to close'; exit 1 }

# adb from PATH, from a platform-tools folder next to the scripts, or downloaded from Google.
function Find-Adb([switch]$NoDownload) {
  $adb = (Get-Command adb.exe -ErrorAction SilentlyContinue).Source
  foreach ($p in @("$PSScriptRoot\platform-tools\adb.exe", "$PSScriptRoot\..\firetv-clean\platform-tools\adb.exe")) {
    if (-not $adb -and (Test-Path $p)) { $adb = (Resolve-Path $p).Path }
  }
  if ($adb) { return $adb }
  if ($NoDownload) { Fail 'adb not found: run Install-GlassHome.bat once first.' }
  Say 'Downloading Android platform-tools (adb) from dl.google.com'
  $zip = "$PSScriptRoot\platform-tools.zip"
  try {
    Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -UseBasicParsing -ErrorAction Stop
    Expand-Archive $zip -DestinationPath $PSScriptRoot -Force -ErrorAction Stop
    Remove-Item $zip
  } catch { Fail "Could not download adb: $_" }
  return "$PSScriptRoot\platform-tools\adb.exe"
}

# Runs adb against the connected TV ($Adb and $Serial are set by the calling script).
function A { & $Adb -s $Serial @args 2>&1 }
function Sh([string]$cmd) { ((& $Adb -s $Serial shell $cmd 2>&1) -join "`n").Trim() }

# Connects to the TV and returns its adb serial (ip:port). Uses, in order: the address given on
# the command line, a TV adb is already connected to over the network, or asks (remembering the
# last answer in tv-ip.txt).
function Connect-Tv([string]$Ip) {
  $saved = "$PSScriptRoot\tv-ip.txt"
  if (-not $Ip) {
    $live = @(& $Adb devices 2>$null | Where-Object { $_ -match '^(\d{1,3}(\.\d{1,3}){3}:\d+)\s+device$' } |
              ForEach-Object { ($_ -split '\s+')[0] })
    if ($live.Count -eq 1) { $Ip = $live[0]; Ok "using the TV adb is already connected to: $Ip" }
  }
  if (-not $Ip) {
    $default = if (Test-Path $saved) { (Get-Content $saved | Select-Object -First 1).Trim() } else { '' }
    $prompt = if ($default) { "TV IPv4 address [$default]" } else { 'TV IPv4 address (Settings > Network on the TV)' }
    $Ip = (Read-Host $prompt).Trim()
    if (-not $Ip) { $Ip = $default }
  }
  if (-not $Ip) { Fail 'No address given.' }
  $Ip = $Ip.Trim()
  $serial = if ($Ip -match ':\d+$') { $Ip } else { "${Ip}:5555" }
  Set-Content $saved ($serial -replace ':5555$', '')
  & $Adb connect $serial | Out-Host
  Write-Host '  If the TV asks "Allow USB debugging?", tick "Always allow" and press Allow.'
  $state = ''
  for ($i = 0; $i -lt 30; $i++) {
    $state = ((& $Adb -s $serial get-state 2>$null) -join '').Trim()
    if ($state -eq 'device') { break }
    Start-Sleep 2
    if ($i % 5 -eq 4) { & $Adb connect $serial | Out-Null }
  }
  if ($state -ne 'device') { Fail "The TV did not connect ($state). Check the address, that ADB debugging is on, and press Allow on the TV." }
  return $serial
}

# Stops unless the connected device is a Fire TV on Fire OS 7 or newer; returns its SDK level.
function Assert-FireTv {
  $maker = Sh 'getprop ro.product.manufacturer'
  $model = Sh 'getprop ro.product.model'
  $sdk   = [int](Sh 'getprop ro.build.version.sdk')
  Ok "connected: $maker $model (Android sdk $sdk)"
  # Fire TV Edition sets (Toshiba, Insignia...) may report the TV maker, so also accept any
  # device with Amazon's Fire TV home screen.
  $amazonHome = (Sh 'pm path com.amazon.tv.launcher') -match 'package:'
  if ($maker -notlike 'Amazon*' -and -not $amazonHome) { Fail 'This is not a Fire TV. Stopping without changes.' }
  if ($sdk -lt 28) { Fail 'This needs Fire OS 7 or newer.' }
  return $sdk
}
