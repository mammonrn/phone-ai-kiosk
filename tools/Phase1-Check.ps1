<#
.SYNOPSIS
    Checks the phase 1 kiosk on a connected device over adb.

.DESCRIPTION
    With no switches this script only READS from the device. Nothing it does
    by default changes the phone, installs, uninstalls, reboots or taps.

    The two tests that do change the phone are opt-in and ask before acting:

      -RebootTest   reboots the device and re-checks that the kiosk comes back
      -ExitTest     taps the hidden corner 10 times to leave lock task

    Pass -Yes to skip the confirmation prompts.

.EXAMPLE
    .\Phase1-Check.ps1
    Read-only status.

.EXAMPLE
    .\Phase1-Check.ps1 -RebootTest
    Asks, reboots, waits for boot, then re-checks.

.EXAMPLE
    .\Phase1-Check.ps1 -ExitTest -TapX 650 -TapY 1440
    Asks, then sends 10 taps to that point.

.NOTES
    Never disables USB debugging. adb is the only recovery path off a device
    this app is Device Owner of.
#>
[CmdletBinding()]
param(
    [string] $Package     = 'com.mammonrn.phoneaikiosk.debug',
    [string] $AdminClass  = 'com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver',
    [switch] $RebootTest,
    [switch] $ExitTest,
    [int]    $TapX = 650,
    [int]    $TapY = 1440,
    [switch] $Yes
)

$ErrorActionPreference = 'Stop'
$script:Failures = 0

function Write-Ok    { param([string]$Message) Write-Host "[ ok ] $Message" -ForegroundColor Green }
function Write-Info  { param([string]$Message) Write-Host "[info] $Message" -ForegroundColor Gray }
function Write-Warn  { param([string]$Message) Write-Host "[warn] $Message" -ForegroundColor Yellow }
function Write-Fail  {
    param([string]$Message)
    Write-Host "[fail] $Message" -ForegroundColor Red
    $script:Failures++
}

function Write-Section {
    param([string]$Title)
    Write-Host ''
    Write-Host "== $Title " -ForegroundColor Cyan -NoNewline
    Write-Host ('=' * [Math]::Max(0, 60 - $Title.Length)) -ForegroundColor Cyan
}

# adb writes some ordinary progress text to stderr, which PowerShell turns into
# error records. Redirecting 2>&1 and taking the strings keeps that out of the
# way without hiding real failures, which show up in the text itself.
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $AdbArgs)
    (& adb @AdbArgs 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
}

function Confirm-Action {
    param([string] $What)
    if ($Yes) { return $true }
    Write-Host ''
    Write-Host "About to $What" -ForegroundColor Yellow
    $answer = Read-Host "Type YES to continue"
    if ($answer -ceq 'YES') { return $true }
    Write-Info 'Skipped.'
    return $false
}

$AdminComponent = "$Package/$AdminClass"

# ---------------------------------------------------------------- environment

Write-Section 'adb and device'

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Fail 'adb is not on PATH. Install platform-tools and add it, then re-run.'
    exit 1
}

$devices = Invoke-Adb 'devices'
$deviceLines = @($devices -split "`n" | Where-Object { $_ -match '^\S+\s+(device|unauthorized|offline)\s*$' })

if ($deviceLines.Count -eq 0) {
    Write-Fail 'No device. Check the cable, and that the phone is not asleep.'
    exit 1
}
if ($deviceLines -match 'unauthorized') {
    Write-Fail 'Device is unauthorized.'
    Write-Info 'On the phone: Settings > Developer options > Revoke USB debugging authorizations,'
    Write-Info 'then unplug, replug, and tap Allow on the prompt. See TESTING.md.'
    exit 1
}
if ($deviceLines.Count -gt 1) {
    Write-Warn "More than one device attached; adb will refuse to pick. Use `$env:ANDROID_SERIAL."
}
Write-Ok "Device connected: $(($deviceLines[0] -split '\s+')[0])"

# ------------------------------------------------------------------- accounts

Write-Section 'Accounts (must be zero before set-device-owner)'

$accountDump = Invoke-Adb 'shell' 'dumpsys' 'account'
$accountMatches = [regex]::Matches($accountDump, 'Account\s*\{name=(?<name>[^,]*),\s*type=(?<type>[^\}]*)\}')

if ($accountMatches.Count -eq 0) {
    Write-Ok 'No accounts on the device.'
} else {
    Write-Warn "$($accountMatches.Count) account(s) present. dpm set-device-owner will refuse to run."
    foreach ($m in $accountMatches) {
        $type = $m.Groups['type'].Value.Trim()
        Write-Info "  type=$type"
        if ($type -eq 'com.google.android.apps.tachyon') {
            Write-Info '  ^ this is Google Meet, which creates an account with no entry in Settings.'
            Write-Info '    Remove it with:  adb shell pm uninstall --user 0 com.google.android.apps.tachyon'
            Write-Info '    Restore it with: adb shell cmd package install-existing com.google.android.apps.tachyon'
        }
    }
}

# ---------------------------------------------------------------------- owner

Write-Section 'Device Owner'

$owners = Invoke-Adb 'shell' 'dpm' 'list-owners'
if ($owners -match [regex]::Escape($Package)) {
    Write-Ok "Device Owner is $Package"
} else {
    Write-Warn 'This package is not the Device Owner.'
    Write-Info "  Set:    adb shell dpm set-device-owner $AdminComponent"
    Write-Info "  Remove: adb shell dpm remove-active-admin $AdminComponent"
}
Write-Info ($owners.Trim())

# ------------------------------------------------------------------ installed

Write-Section 'Installed package'

$packages = Invoke-Adb 'shell' 'pm' 'list' 'packages' $Package
if ($packages -match [regex]::Escape($Package)) {
    Write-Ok "$Package is installed"
    $packageDump = Invoke-Adb 'shell' 'dumpsys' 'package' $Package
    foreach ($field in 'versionName', 'versionCode') {
        $m = [regex]::Match($packageDump, "$field=(?<v>\S+)")
        if ($m.Success) { Write-Info "  $field=$($m.Groups['v'].Value)" }
    }
    $updated = [regex]::Match($packageDump, 'lastUpdateTime=(?<v>.*)')
    if ($updated.Success) { Write-Info "  lastUpdateTime=$($updated.Groups['v'].Value.Trim())" }
} else {
    Write-Fail "$Package is not installed. Install with: adb install -t -r app-debug.apk"
}

# ----------------------------------------------------------------------- home

Write-Section 'HOME resolution (this is what makes a reboot land in the kiosk)'

$homeResolution = Invoke-Adb 'shell' 'cmd' 'package' 'resolve-activity' '--brief' '-c' 'android.intent.category.HOME' '-a' 'android.intent.action.MAIN'
if ($homeResolution -match [regex]::Escape($Package)) {
    Write-Ok 'HOME resolves to this app'
} else {
    Write-Warn 'HOME does not resolve to this app; a reboot will not land in the kiosk.'
}
Write-Info ($homeResolution.Trim() -split "`n" | Select-Object -Last 1)

# ------------------------------------------------------------------ lock task

function Show-ActivityState {
    $dump = Invoke-Adb 'shell' 'dumpsys' 'activity' 'activities'

    $state = [regex]::Match($dump, 'mLockTaskModeState=(?<s>\w+)')
    if ($state.Success) {
        $value = $state.Groups['s'].Value
        switch ($value) {
            'LOCKED' { Write-Ok  "mLockTaskModeState=LOCKED" }
            'PINNED' { Write-Warn "mLockTaskModeState=PINNED - this is screen pinning, not lock task. The package is not on the allowlist." }
            default  { Write-Warn "mLockTaskModeState=$value" }
        }
    } else {
        Write-Warn 'Could not read mLockTaskModeState from dumpsys.'
    }

    # The allowlist is NOT on the same line as its header. AOSP prints
    # "mLockTaskPackages (userId:packages)=" and then one line per user
    # underneath, so any filter that keeps only matching lines drops the
    # values and makes an allowlist that is full look empty.
    $pkgs = [regex]::Match($dump, 'mLockTaskPackages \(userId:packages\)=\r?\n(?<body>(?:[ \t]+u\d+:\[[^\]]*\]\r?\n)*)')
    if ($pkgs.Success -and $pkgs.Groups['body'].Value.Trim()) {
        Write-Ok 'mLockTaskPackages:'
        foreach ($line in ($pkgs.Groups['body'].Value -split "`n")) {
            if ($line.Trim()) { Write-Info "  $($line.Trim())" }
        }
    } else {
        Write-Warn 'mLockTaskPackages is genuinely empty (no lines under the header).'
    }

    $top = [regex]::Match($dump, 'topResumedActivity=.*?(?<c>\S+/\S+)')
    if (-not $top.Success) {
        $top = [regex]::Match($dump, 'ResumedActivity:.*?(?<c>\S+/\S+)')
    }
    if ($top.Success) {
        $component = $top.Groups['c'].Value
        if ($component -match [regex]::Escape($Package)) {
            Write-Ok "top activity: $component"
        } else {
            Write-Info "top activity: $component"
        }
    } else {
        Write-Warn 'Could not read the resumed activity from dumpsys.'
    }
}

Write-Section 'Lock task state'
Show-ActivityState

# ------------------------------------------------------------------- optional

if ($RebootTest) {
    Write-Section 'Reboot test'
    if (Confirm-Action 'REBOOT the device and wait for it to come back.') {
        Invoke-Adb 'reboot' | Out-Null
        Write-Info 'Rebooting; waiting for the device...'
        Invoke-Adb 'wait-for-device' | Out-Null

        $deadline = (Get-Date).AddMinutes(3)
        while ((Get-Date) -lt $deadline) {
            if ((Invoke-Adb 'shell' 'getprop' 'sys.boot_completed').Trim() -eq '1') { break }
            Start-Sleep -Seconds 3
        }
        # The launcher needs a moment after boot_completed before dumpsys
        # reports a resumed activity.
        Start-Sleep -Seconds 8
        Write-Info 'Booted. State now:'
        Show-ActivityState
        Write-Info 'Expected: mLockTaskModeState=LOCKED and the top activity is this app.'
    }
}

if ($ExitTest) {
    Write-Section 'Exit test (10 taps)'
    if (Confirm-Action "tap ($TapX, $TapY) ten times, which LEAVES the kiosk.") {
        for ($i = 1; $i -le 10; $i++) {
            Invoke-Adb 'shell' 'input' 'tap' "$TapX" "$TapY" | Out-Null
            Write-Info "  tap $i/10"
            Start-Sleep -Milliseconds 300
        }
        Start-Sleep -Seconds 2
        Write-Info 'State now:'
        Show-ActivityState
        Write-Info 'Expected: mLockTaskModeState=NONE and the top activity is the Samsung launcher.'
        Write-Info 'Pressing HOME returns to this app, still NONE. Rebooting makes it LOCKED again.'
    }
}

# -------------------------------------------------------------------- summary

Write-Section 'Summary'
if ($script:Failures -eq 0) {
    Write-Ok 'No blocking problems found.'
} else {
    Write-Fail "$($script:Failures) check(s) failed."
}
Write-Info 'Recovery, if the phone ever gets stuck:'
Write-Info "  adb shell dpm remove-active-admin $AdminComponent"
Write-Info "  adb uninstall $Package"

exit $script:Failures
