# Clean-FireTV.ps1 - switches off Amazon's adverts, tracking and junk on a Fire TV, keeps the TV
# working, and makes it feel quicker. Everything it changes is written to cleaned.txt next to this
# script, and Restore-FireTV.bat puts all of it back.
# Run: double-click Clean-FireTV.bat (or: Clean-FireTV.bat 10.0.0.5)
#
# Nothing here is deleted from the TV. Apps are switched off for the user ("disable-user"), or,
# where Fire OS refuses that, hidden for the user with their files kept; both undo with one
# command. Never touched: Amazon's home screen and settings, the Appstore (app updates), HDMI and
# live TV inputs, the keyboard, Alexa and the voice remote, the TV maker's own system apps, and
# any app you installed yourself.
param([string]$Ip, [switch]$Inner)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\TvCommon.ps1"
$Log = "$PSScriptRoot\cleaned.txt"

# ---- what gets switched off (packages that are not on this TV are skipped)
$Adverts = [ordered]@{
  'com.amazon.kso'                   = 'sponsored screensavers and banner adverts ("special offers")'
  'com.amazon.device.sale.service'   = 'deals and sale promotions'
  'com.amazon.bueller.photos'        = 'Amazon Photos screensaver with featured adverts'
  'com.amazon.imdb.tv.android.app'   = 'Freevee, free TV with adverts'
}
$Tracking = [ordered]@{
  'com.amazon.device.crashmanager'   = 'crash and diagnostics uploads'
  'com.amazon.client.metrics'        = 'usage metrics uploads'
  'com.amazon.tv.fw.metrics'         = 'TV framework metrics'
  'com.amazon.logan'                 = 'usage logging'
  'com.amazon.dp.logger'             = 'device logging'
  'com.amazon.wifilocker'            = 'copies your Wi-Fi passwords to your Amazon account'
}
$Junk = [ordered]@{
  'com.amazon.ags.app'               = 'GameCircle'
  'com.amazon.tv.csapp'              = 'help and tips videos'
  'com.amazon.tv.releasenotes'       = 'release notes pop-ups'
}
$Updates = [ordered]@{
  'com.amazon.device.software.ota'          = 'Fire OS updates'
  'com.amazon.device.software.ota.override' = 'Fire OS updates (override)'
  'com.amazon.tv.forcedotaupdater.v2'       = 'forced Fire OS updates'
}
# Ad-blocking DNS for every app on the TV (encrypted, no account, run by AdGuard).
$AdDns = 'dns.adguard-dns.com'

# ---- the log: one line per change, so Restore-FireTV can undo exactly what was done.
#   app <package> disabled|hidden
#   setting <namespace> <key> <original value, or null>
$done = @{}
if (Test-Path $Log) {
  foreach ($l in Get-Content $Log) {
    $f = $l -split ' '
    if ($f[0] -eq 'app' -and $f.Count -ge 3) { $done["app $($f[1])"] = $true }
    elseif ($f[0] -eq 'setting' -and $f.Count -ge 4) { $done["$($f[1]) $($f[2])"] = $true }
  }
}
function Record([string]$line) { Add-Content $Log $line }

function Switch-Off($pkg, $why) {
  if ((Sh "pm path $pkg") -notmatch 'package:') { return }
  if ($done.ContainsKey("app $pkg")) { Ok "already off: $why"; return }
  $r = Sh "pm disable-user --user 0 $pkg"
  if ($r -match 'new state: disabled') {
    Record "app $pkg disabled"; $done["app $pkg"] = $true; Ok "off: $why"; return
  }
  $r = Sh "pm uninstall -k --user 0 $pkg"
  if ($r -match 'Success') {
    Record "app $pkg hidden"; $done["app $pkg"] = $true; Ok "off: $why"; return
  }
  Warn "Fire OS would not switch off $pkg ($why)"
}

# Changes one setting, remembering its original value the first time only.
function Set-TvSetting([string]$ns, [string]$key, [string]$value) {
  if (-not $done.ContainsKey("$ns $key")) {
    $old = Sh "settings get $ns $key"
    if (-not $old) { $old = 'null' }
    Record "setting $ns $key $old"
    $done["$ns $key"] = $true
  }
  Sh "settings put $ns $key $value" | Out-Null
}

function Ask([string]$q, [bool]$yes) {
  $a = (Read-Host ("$q " + $(if ($yes) { '(Y/n)' } else { '(y/N)' }))).Trim()
  if (-not $a) { return $yes }
  return $a -match '^(y|yes)$'
}

# ---- connect
$Adb = Find-Adb
Say 'Connecting to the TV'
$Serial = Connect-Tv $Ip
$sdk = Assert-FireTv

# ---- adverts, tracking, junk
Say 'Switching off adverts, tracking and junk'
foreach ($list in @($Adverts, $Tracking, $Junk)) {
  foreach ($p in $list.Keys) { Switch-Off $p $list[$p] }
}

# ---- adverts inside apps: DNS that refuses ad and tracker servers
Say 'Blocking adverts in every app'
Write-Host "  Sends the TV's DNS lookups, encrypted, to $AdDns, which refuses advert and tracker"
Write-Host '  servers. Removes most adverts in free apps and banners; it cannot remove adverts that'
Write-Host '  are part of the video stream itself (YouTube, Freevee). A few ad-funded apps may refuse'
Write-Host '  to play; Restore-FireTV.bat turns it off again.'
if (Ask 'Turn on ad-blocking DNS?' $true) {
  $oldMode = Sh 'settings get global private_dns_mode'
  $oldHost = Sh 'settings get global private_dns_specifier'
  Set-TvSetting global private_dns_mode hostname
  Set-TvSetting global private_dns_specifier $AdDns
  # ping prints "PING name (address)" once the name resolves; that is all this checks, so a
  # network that drops ping still counts as working.
  $works = $false
  for ($i = 0; $i -lt 6 -and -not $works; $i++) {
    Start-Sleep 2
    $works = (Sh 'ping -c 1 -W 2 www.amazon.com') -match 'PING \S+ \(\d'
  }
  if ($works) { Ok "ad-blocking DNS on ($AdDns)" }
  else {
    # Never leave the TV without internet: put the previous DNS back straight away.
    Warn "The TV could not look names up through $AdDns, so the previous DNS is back."
    foreach ($kv in @(@('private_dns_mode', $oldMode), @('private_dns_specifier', $oldHost))) {
      if (-not $kv[1] -or $kv[1] -eq 'null') { Sh "settings delete global $($kv[0])" | Out-Null }
      else { Sh "settings put global $($kv[0]) $($kv[1])" | Out-Null }
    }
  }
}

# ---- Fire OS updates (they switch adverts back on)
Say 'Fire OS updates'
Write-Host '  Amazon updates often switch adverts and tracking back on. Turning updates off keeps'
Write-Host '  this clean, but you also miss security fixes. Apps still update from the Appstore.'
if (Ask 'Turn off automatic Fire OS updates?' $false) {
  foreach ($p in $Updates.Keys) { Switch-Off $p $Updates[$p] }
} else { Ok 'updates left on (run Clean-FireTV.bat again after an update)' }

# ---- quicker, smoother feel
Say 'Making the TV feel quicker'
# Opening and closing apps twice as fast; animations inside apps (Glass Home's glide) at full
# smoothness. 0 here would make Glass Home jump instead of glide, so it is never used.
Set-TvSetting global window_animation_scale 0.5
Set-TvSetting global transition_animation_scale 0.5
Set-TvSetting global animator_duration_scale 1.0
Ok 'app open and close animations: twice as fast'
Sh 'am kill-all' | Out-Null
Ok 'background apps closed to free memory'

Say 'Clean'
Write-Host "  Every change is listed in $Log"
Write-Host '  Restore-FireTV.bat puts all of it back.'
Write-Host ''
Write-Host '  On the TV, also turn these off (Fire OS keeps them out of reach of adb):'
Write-Host '    Settings > Preferences > Privacy Settings > Device Usage Data, Collect App Usage Data,'
Write-Host '      Interest-based Ads'
Write-Host '    Settings > Preferences > Featured Content > Allow Video and Audio Autoplay'
if (-not $Inner) {
  & $Adb disconnect $Serial | Out-Null
  Write-Host ''
  Write-Host '  Turn ADB debugging OFF on the TV when you are done (Settings > My Fire TV > Developer Options).' -ForegroundColor Yellow
  Read-Host 'Press Enter to close'
}
