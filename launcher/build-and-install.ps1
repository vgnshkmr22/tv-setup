# Windows version of build-and-install.sh: build the launcher APK and install it on the TV as the
# default home screen. Uses Android Studio's bundled JDK and the default SDK location unless
# JAVA_HOME / ANDROID_HOME are set.
#
# Usage: launcher\build-and-install.ps1 -TvHost 192.168.1.50 [-StockLauncher <package>|none]
#        The stock launcher is detected from the TV when -StockLauncher is not given.
#        [-InputKeyService]   remote's Input button opens the input switcher (Google TV); re-pass on every install
#        [-HomeKeyService]    remotes whose Home button sends KEYCODE_ALL_APPS (Xiaomi sticks)
#        [-Channel official]  download the latest release APK instead of building; it updates itself on the
#                             TV from then on (docs/specs/self-update.md). Default: local, built here.
#        [-Migrate [-Yes]]    switch a TV whose launcher was signed with another key (usually: built here,
#                             moving to -Channel official). Saves config.xml and the theme overlay, asks,
#                             uninstalls, installs, puts the settings back; module card answers are lost.
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = '',
    [ValidateSet('local', 'official')]
    [string]$Channel = $(if ($env:CHANNEL) { $env:CHANNEL } else { 'local' }),
    [switch]$Migrate,
    [switch]$Yes,
    [switch]$InputKeyService,
    [switch]$HomeKeyService
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
Set-Location $PSScriptRoot

if ($env:MIGRATE -eq '1') { $Migrate = $true }
if ($env:MIGRATE_CONFIRM -eq 'yes') { $Yes = $true }
$pkg = 'com.gathin.tvhome'
$files = "/sdcard/Android/data/$pkg/files"
$py = '..\tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'run: python -m venv tools\venv; tools\venv\Scripts\pip install -r tools\requirements.txt' }
function Tv { & $py -I ..\tools\tv.py @args }

if ($Channel -eq 'local') {
    if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
        if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder)' }
        $env:JAVA_HOME = $jbr
    }
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
    if (-not (Test-Path local.properties)) {
        "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
    }
    .\gradlew.bat assembleRelease -q --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
    $apk = 'app\build\outputs\apk\release\app-release.apk'
} else {
    New-Item -ItemType Directory -Force app\build\official | Out-Null
    $apk = 'app\build\official\cleanyourtv.apk'
    Remove-Item -Force -ErrorAction SilentlyContinue $apk
    & $py -I ..\tools\official_apk.py $apk
    if ($LASTEXITCODE -ne 0) { throw 'could not download a verified official APK; see the line above' }
}

# Which app owns Home differs by build; ask the TV, but only accept a launcher we recognise (the answer
# can also be Android's chooser "android", the setup wizard, or another third-party launcher).
$knownStock = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher')
# Migrating: the stock launcher has to hold Home while ours is uninstalled, so find which one this TV has.
if ($Migrate -and -not $StockLauncher) {
    $installed = (Tv 'pm list packages') | ForEach-Object { "$_".Trim() }
    $StockLauncher = $knownStock | Where-Object { $installed -contains "package:$_" } | Select-Object -First 1
    if (-not $StockLauncher) { throw 'no stock launcher this script knows is installed; pass -StockLauncher <package>' }
}
if (-not $StockLauncher) {
    $homePkg = & $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0 -or -not $homePkg) { throw 'could not ask the TV which launcher owns Home; check -TvHost and the debugging prompt, or pass -StockLauncher <package>' }
    $homePkg = "$homePkg".Trim().Split('/')[0]
    if ($knownStock -contains $homePkg) { $StockLauncher = $homePkg }
    elseif ($homePkg -eq 'com.gathin.tvhome') { $StockLauncher = 'none'; Write-Host 'Home already belongs to this launcher; nothing will be disabled.' }
    elseif ($homePkg -eq 'android') {
        # Android's chooser: launchers tie and no default is set (a restore followed by a re-applied list
        # ends here). Take the one known stock launcher among the HOME claimants, if there is exactly one.
        $claimants = (& $py -I ..\tools\tv.py 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -join "`n"
        $found = @($knownStock | Where-Object { $claimants -match ([regex]::Escape($_) + '/') })
        if ($found.Count -eq 1) { $StockLauncher = $found[0]; Write-Host "Home is Android's chooser; the stock launcher among the candidates is $StockLauncher." }
        else { throw "Home is Android's launcher chooser and $($found.Count) known stock launchers claim Home: pick a default on the TV first, or run again with -StockLauncher <package to disable> or -StockLauncher none." }
    }
    else { throw "Home currently resolves to '$homePkg', which is not a stock launcher this script knows (android = launcher chooser, com.google.android.tungsten.setupwraith = setup wizard, see README Troubleshooting; anything else = another launcher is the default). Run again with -StockLauncher <package to disable> or -StockLauncher none." }
}
Write-Host "Stock launcher: $StockLauncher"
$disableStock = if ($StockLauncher -eq 'none') { 'echo stock launcher left alone' } else { "pm disable-user --user 0 $StockLauncher" }

$backup = $null
if ($Migrate -and -not ((Tv "pm list packages $pkg") | Where-Object { "$_".Trim() -eq "package:$pkg" })) {
    Write-Host "-Migrate: the launcher isn't installed on this TV, so there is nothing to migrate."
    $Migrate = $false
}
if ($Migrate) {
    # 1. Save the overlay and wallpaper the TV has, and turn config.xml into an overlay underneath them.
    $backup = "..\backup\launcher-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    New-Item -ItemType Directory -Force $backup | Out-Null
    $onTv = (Tv "ls $files 2>/dev/null") | ForEach-Object { "$_".Trim() }
    foreach ($f in 'config.json', 'wallpaper.jpg') {
        if ($onTv -contains $f) { Tv --pull "$files/$f" "$backup\tv-$f" }
    }
    $convert = @('..\tools\config2json.py', 'app\src\main\res\values\config.xml', "$backup\config.json")
    if (Test-Path "$backup\tv-config.json") { $convert += "$backup\tv-config.json" }
    & $py -I @convert
    if ($LASTEXITCODE -ne 0) { throw 'could not convert config.xml' }
    if (Test-Path "$backup\tv-wallpaper.jpg") { Copy-Item "$backup\tv-wallpaper.jpg" "$backup\wallpaper.jpg" }
    Write-Host "Saved to $backup."
    # 2. Ask, then hand Home to the stock launcher and 3. uninstall ours.
    Write-Host "-Migrate will uninstall $pkg from the TV and install the new APK in its place."
    Write-Host "  kept:  your config.xml settings and the TV's theme overlay, as $backup\config.json"
    Write-Host '  lost:  your answers to module card prompts; the TV asks again'
    Write-Host "  Home:  $StockLauncher is enabled while the launcher is away"
    if (-not $Yes -and (Read-Host 'Type yes to go on') -ne 'yes') {
        throw 'Stopped; nothing on the TV was changed. Run again with -Yes to skip the question.'
    }
    Tv "pm enable --user 0 $StockLauncher" | Out-Null
    Tv "cmd package set-home-activity --user 0 $StockLauncher" | Out-Null
    $out = Tv "pm uninstall $pkg"
    if ("$out" -notmatch 'Success') { throw "pm uninstall said: $out" }
    Write-Host "Uninstalled the old launcher; $StockLauncher holds Home."
}

& $py -I ..\tools\b64push.py $apk /data/local/tmp/tvhome.apk
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
$out = & $py -I ..\tools\tv.py 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
Write-Host $out
if ("$out" -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') { throw 'the launcher on the TV is signed with another key. To replace it, run again with -Migrate.' }
if ("$out" -notmatch 'Success') {
    if ($backup) { Write-Host "Your settings are saved in $backup; $StockLauncher holds Home meanwhile." }
    throw 'pm install failed; see the line above and docs/TROUBLESHOOTING.md'
}
# Android 9 only accepts a component here; Android 10+ also takes a bare package name.
$out = & $py -I ..\tools\tv.py 'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity'
Write-Host $out
if ("$out" -match 'Error') { throw 'could not set the new launcher as home; the stock launcher was left untouched' }
# Grants for self-update, best effort: "Install unknown apps" (many TVs have no screen for it) and
# WRITE_SECURE_SETTINGS, which lets the launcher switch its accessibility services back on after updating.
@(
    "appops set $pkg REQUEST_INSTALL_PACKAGES allow 2>/dev/null && echo install-permission-granted || echo install-permission-not-granted",
    "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>/dev/null && echo secure-settings-granted || echo secure-settings-not-granted",
    'pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted',
    'cmd package compile -m speed -f com.gathin.tvhome',
    "am start -W -n com.gathin.tvhome/.MainActivity | grep -E '^(Status|Error)'",
    $disableStock,
    'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity'
) | & $py -I ..\tools\tv.py --batch
# Order matters, see build-and-install.sh: start the new launcher, disable the stock one, set the
# preference last, then check where Home ended up and put the stock launcher back if it is not ours.
$homeNow = (& $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | Select-Object -Last 1).Trim()
if ($homeNow -notlike 'com.gathin.tvhome/*') {
    if ($StockLauncher -ne 'none') { & $py -I ..\tools\tv.py "pm enable --user 0 $StockLauncher" | Out-Null }
    throw "Home resolves to '$homeNow', not to the new launcher; $StockLauncher was re-enabled. On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home: add it to your debloat list (see README, Troubleshooting), apply it, then run this script again."
}
Write-Host "Home now resolves to $homeNow"
# Optional accessibility services, appended to enabled_accessibility_services without dropping others.
function Enable-Service([string]$Name) {
    $svc = "com.gathin.tvhome/.$Name"
    & $py -I ..\tools\tv.py ('s=$(settings get secure enabled_accessibility_services); case "$s" in *' + $Name + '*) ;; null|"") settings put secure enabled_accessibility_services ' + $svc + ' ;; *) settings put secure enabled_accessibility_services "$s:' + $svc + '" ;; esac; settings put secure accessibility_enabled 1; echo ' + $Name + ' enabled')
}
if ($InputKeyService) { Enable-Service 'InputKeyService' }
if ($HomeKeyService) { Enable-Service 'HomeKeyService' }
# 4. Put the saved overlay back and restart Home so it reads it, as tools\theme.ps1 does.
if ($backup) {
    Tv "mkdir -p $files" | Out-Null
    Tv --push "$backup\config.json" "$files/config.json"
    if (Test-Path "$backup\wallpaper.jpg") { & $py -I ..\tools\b64push.py "$backup\wallpaper.jpg" "$files/wallpaper.jpg" }
    Tv "am force-stop $pkg; am start -n $pkg/.MainActivity >/dev/null; echo settings-restored" | Select-Object -Last 1
}
Write-Host 'Installed. Press Home on the remote to see it.'
