# Uninstall-GlassHome.ps1 - gives HOME back to Amazon's home screen and removes Glass Home.
param([string]$Ip)
$ErrorActionPreference = 'Continue'
$Pkg = 'tv.glasshome'
. "$PSScriptRoot\TvCommon.ps1"
$Adb = Find-Adb -NoDownload
$Serial = Connect-Tv $Ip

$svc = Sh 'settings get secure enabled_accessibility_services'
if ($svc -eq 'null') { $svc = '' }
$keep = @($svc -split ':' | Where-Object { $_ -and $_ -notlike "$Pkg/*" })
if ($keep.Count -gt 0) { Sh "settings put secure enabled_accessibility_services $($keep -join ':')" | Out-Null }
else { Sh 'settings delete secure enabled_accessibility_services' | Out-Null; Sh 'settings put secure accessibility_enabled 0' | Out-Null }

if ((Sh 'settings get secure screensaver_components') -like "$Pkg/*") {
  $saver = Sh 'settings get global glasshome_screensaver'
  if ($saver -and $saver -ne 'null') { Sh "settings put secure screensaver_components $saver" | Out-Null }
  else { Sh 'settings delete secure screensaver_components' | Out-Null }
}
Sh 'settings delete global glasshome_screensaver' | Out-Null

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
