# Install-GlassHome.ps1 - puts Glass Home on a Fire TV over Wi-Fi and gives it the HOME button.
# Keep glass-home.apk and glass-home.apk.sha256 in the same folder as this script.
# Run: double-click Install-GlassHome.bat
param([string]$Ip)   # optional: Install-GlassHome.bat 10.0.0.5
$ErrorActionPreference = 'Continue'   # adb writes to stderr; every step is checked explicitly
$Here = $PSScriptRoot
$Pkg  = 'tv.glasshome'
$GuardId = 'tv.glasshome/tv.glasshome.Guard'
. "$Here\TvCommon.ps1"

$Adb = Find-Adb

# ---- the APK
Say 'Checking the app file'
$Apk = "$Here\glass-home.apk"
if (-not (Test-Path $Apk)) { Fail "glass-home.apk is missing from $Here" }
$want = (Get-Content "$Here\glass-home.apk.sha256" -ErrorAction SilentlyContinue | Select-Object -First 1)
$got  = (Get-FileHash $Apk -Algorithm SHA256).Hash.ToLower()
if ($want -and $want.Trim().ToLower() -ne $got) { Fail "Checksum mismatch: the APK is not the one GitHub built. Not installing." }
Ok "glass-home.apk  sha256 $got"

# ---- connect
Say 'Connecting to the TV'
$Serial = Connect-Tv $Ip
$sdk = Assert-FireTv

# ---- install
Say 'Installing Glass Home'
$out = (A install -r "$Apk") -join "`n"
if ($out -notmatch 'Success') { Fail "Install failed: $out" }
Ok 'installed'
Sh "dumpsys deviceidle whitelist +$Pkg" | Out-Null

# ---- HOME takeover (the same method dumbtv home uses on Fire OS)
Say 'Taking over the HOME button'
# 1. Alarms may fire immediately, so the switch from Amazon's home is instant.
if ($sdk -ge 31) {
  Sh 'device_config put alarm_manager min_futurity 0' | Out-Null
} else {
  $cur = Sh 'settings get global alarm_manager_constants'
  if ($cur -eq 'null') { $cur = '' }
  $parts = @($cur -split ',' | Where-Object { $_ -and $_ -notlike 'min_futurity=*' })
  $old = ($cur -split ',' | Where-Object { $_ -like 'min_futurity=*' } | Select-Object -First 1)
  if ($old -and $old -ne 'min_futurity=0') { Sh "settings put global glasshome_min_futurity $($old.Split('=')[1])" | Out-Null }
  $parts += 'min_futurity=0'
  Sh "settings put global alarm_manager_constants $($parts -join ',')" | Out-Null
}
# 2. The accessibility helper that answers HOME. Any older home helper (dumbtv) is switched off.
$svc = Sh 'settings get secure enabled_accessibility_services'
if ($svc -eq 'null') { $svc = '' }
$keep = @($svc -split ':' | Where-Object { $_ -and $_ -notlike 'tv.dumbtv.home/*' -and $_ -notlike "$Pkg/*" })
$new = (@($keep) + $GuardId) -join ':'
Sh 'settings delete secure enabled_accessibility_services' | Out-Null
Start-Sleep 1
Sh "settings put secure enabled_accessibility_services $new" | Out-Null
Sh 'settings put secure accessibility_enabled 1' | Out-Null
Sh "cmd package set-home-activity $Pkg/.Home" | Out-Null
$bound = $false
for ($i = 0; $i -lt 15; $i++) {
  if ((Sh 'dumpsys accessibility') -match 'Glass Home button|tv\.glasshome') { $bound = $true; break }
  Start-Sleep 1
}
if ($bound) { Ok 'HOME button: Glass Home' } else { Warn 'Fire OS has not confirmed the HOME helper yet; press HOME on the remote to check.' }
# ---- the clock screensaver (Amazon's is remembered, so Uninstall-GlassHome puts it back)
Say 'Clock screensaver'
$saver = Sh 'settings get secure screensaver_components'
if ($saver -notlike "$Pkg/*") { Sh "settings put global glasshome_screensaver $saver" | Out-Null }
Sh "settings put secure screensaver_components $Pkg/.Clock" | Out-Null
Sh 'settings put secure screensaver_enabled 1' | Out-Null
Ok 'screensaver: Glass Home clock'
Sh 'input keyevent KEYCODE_WAKEUP' | Out-Null
Sh "am start -n $Pkg/.Home" | Out-Null

# ---- faster app starts
Say 'Making your apps start faster (compiling them fully, about 1-3 minutes)'
$third = @((Sh 'pm list packages -3') -split "`n" | ForEach-Object { $_.Trim() -replace '^package:', '' } | Where-Object { $_ -and $_ -ne $Pkg })
foreach ($p in $third) {
  $r = Sh "cmd package compile -m speed $p"
  if ($r -match 'Success') { Ok "fast start: $p" } else { Warn "skipped: $p" }
}

# ---- the old dumbtv home
if ((Sh 'pm path tv.dumbtv.home') -match 'package:') {
  $ans = Read-Host "`nRemove the old dumbtv home app? It is no longer used (y/N)"
  if ($ans -match '^(y|yes)$') { A uninstall tv.dumbtv.home | Out-Host }
}

Say 'Done'
Write-Host '  Press HOME on the remote: Glass Home should appear.'
Write-Host '  Menu button (three lines) on any app: pin to dock, move, hide, app info.'
Write-Host '  Edit (top right) > Open Amazon''s home screen, any time you need it.'
Write-Host ''
$ans = Read-Host 'Also switch off Amazon''s adverts, tracking and junk apps now? (Y/n)'
if ($ans -notmatch '^(n|no)$') { & "$Here\Clean-FireTV.ps1" -Ip $Serial -Inner }
& $Adb disconnect $Serial | Out-Null
Write-Host ''
Write-Host '  Last step: turn ADB debugging OFF on the TV (Settings > My Fire TV > Developer Options).' -ForegroundColor Yellow
Write-Host '  Glass Home keeps working with it off.' -ForegroundColor Yellow
Read-Host 'Press Enter to close'
