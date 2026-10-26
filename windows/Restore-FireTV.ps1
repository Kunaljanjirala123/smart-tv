# Restore-FireTV.ps1 - undoes everything Clean-FireTV did, using the list it kept in cleaned.txt.
# Run: double-click Restore-FireTV.bat (or: Restore-FireTV.bat 10.0.0.5)
param([string]$Ip)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\TvCommon.ps1"
$Log = "$PSScriptRoot\cleaned.txt"
if (-not (Test-Path $Log)) { Fail "Nothing to restore: $Log is not here (Clean-FireTV.bat writes it)." }

$Adb = Find-Adb -NoDownload
Say 'Connecting to the TV'
$Serial = Connect-Tv $Ip
$null = Assert-FireTv

Say 'Putting everything back'
$failed = @()
foreach ($l in Get-Content $Log) {
  $f = $l -split ' '
  if ($f[0] -eq 'app' -and $f.Count -ge 3) {
    $r = if ($f[2] -eq 'hidden') { Sh "cmd package install-existing $($f[1])" } else { Sh "pm enable $($f[1])" }
    if ($r -match 'new state: enabled|installed for user') { Ok "back on: $($f[1])" }
    else { Warn "could not switch $($f[1]) back on: $r"; $failed += $l }
  } elseif ($f[0] -eq 'setting' -and $f.Count -ge 4) {
    $old = ($f[3..($f.Count - 1)] -join ' ')
    if ($old -eq 'null') { Sh "settings delete $($f[1]) $($f[2])" | Out-Null }
    else { Sh "settings put $($f[1]) $($f[2]) $old" | Out-Null }
    Ok "restored: $($f[2])"
  } elseif ($f[0] -eq 'config' -and $f.Count -ge 4) {
    if ($f[3] -eq 'null') { Sh "device_config delete $($f[1]) $($f[2])" | Out-Null }
    else { Sh "device_config put $($f[1]) $($f[2]) $($f[3])" | Out-Null }
    Ok "restored: $($f[2])"
  }
}
if ($failed.Count -gt 0) { Set-Content $Log $failed; Warn "Kept the lines that failed in $Log; run this again to retry them." }
else { Remove-Item $Log; Ok 'everything is back as Amazon set it' }

& $Adb disconnect $Serial | Out-Null
Read-Host 'Press Enter to close'
