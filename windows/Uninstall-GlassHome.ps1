# Uninstall-GlassHome.ps1 - gives HOME back to Amazon's home screen and removes Glass Home.
$ErrorActionPreference = 'Continue'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Pkg = 'tv.glasshome'
$Adb = (Get-Command adb.exe -ErrorAction SilentlyContinue).Source
foreach ($p in @("$Here\platform-tools\adb.exe", "$Here\..\firetv-clean\platform-tools\adb.exe")) {
  if (-not $Adb -and (Test-Path $p)) { $Adb = (Resolve-Path $p).Path }
}
if (-not $Adb) { Write-Host 'adb not found: run Install-GlassHome.bat once first.'; Read-Host; exit 1 }
$saved = "$Here\tv-ip.txt"
$default = if (Test-Path $saved) { (Get-Content $saved | Select-Object -First 1).Trim() } else { '' }
$Ip = (Read-Host "TV IPv4 address [$default]").Trim(); if (-not $Ip) { $Ip = $default }
$Serial = "${Ip}:5555"
& $Adb connect $Serial | Out-Host
function Sh([string]$cmd) { ((& $Adb -s $Serial shell $cmd 2>&1) -join "`n").Trim() }

$svc = Sh 'settings get secure enabled_accessibility_services'
if ($svc -eq 'null') { $svc = '' }
$keep = @($svc -split ':' | Where-Object { $_ -and $_ -notlike "$Pkg/*" })
if ($keep.Count -gt 0) { Sh "settings put secure enabled_accessibility_services $($keep -join ':')" | Out-Null }
else { Sh 'settings delete secure enabled_accessibility_services' | Out-Null; Sh 'settings put secure accessibility_enabled 0' | Out-Null }

$sdk = [int](Sh 'getprop ro.build.version.sdk')
if ($sdk -ge 31) { Sh 'device_config delete alarm_manager min_futurity' | Out-Null }
else {
  $cur = Sh 'settings get global alarm_manager_constants'; if ($cur -eq 'null') { $cur = '' }
  $parts = @($cur -split ',' | Where-Object { $_ -and $_ -notlike 'min_futurity=*' })
  $old = Sh 'settings get global glasshome_min_futurity'
  if ($old -match '^\d+$') { $parts += "min_futurity=$old"; Sh 'settings delete global glasshome_min_futurity' | Out-Null }
  if ($parts.Count -gt 0) { Sh "settings put global alarm_manager_constants $($parts -join ',')" | Out-Null }
  else { Sh 'settings delete global alarm_manager_constants' | Out-Null }
}
& $Adb -s $Serial uninstall $Pkg | Out-Host
Sh 'am start -a android.intent.action.MAIN -c android.intent.category.HOME' | Out-Null
& $Adb disconnect $Serial | Out-Null
Write-Host 'Amazon''s home screen is back and Glass Home is removed.'
Read-Host 'Press Enter to close'
