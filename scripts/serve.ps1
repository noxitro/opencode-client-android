# serve.ps1 — start a real `opencode serve` and leave a record of how it was started.
#
# Why this exists: on 2026-08-23 the running serve's expected password and the value the host
# was using stopped agreeing. Every host-side curl failed while the app kept working, which is
# the signature of a server that is fine and a client that is wrong — but nothing on disk said
# who started the server, when, or from which environment, so the cause was never found.
# TEST_REPORT.md records that as an accident and sends the fix here.
#
# The record below deliberately stores a FINGERPRINT of the password, never the password. Four
# bytes of SHA-256 is enough to tell "these two values differ" — which is the entire question we
# could not answer last time — and is not enough to recover the secret.

[CmdletBinding()]
param(
  [int]$Port = 4097,
  [string]$PasswordVar = 'OPENCODE_SERVER_PASSWORD',
  [string]$Username = 'opencode',
  # Print the fingerprint of the current environment's password and exit, so a host-side client
  # can be compared against a running server without restarting anything.
  [switch]$FingerprintOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$LogPath = Join-Path $RepoRoot 'e2e-artifacts\serve-lifecycle.log'

function Get-Fingerprint([string]$value) {
  if ([string]::IsNullOrEmpty($value)) { return 'ABSENT' }
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($value))
  } finally {
    $sha.Dispose()
  }
  return ([System.BitConverter]::ToString($bytes[0..3]) -replace '-', '')
}

$password = [Environment]::GetEnvironmentVariable($PasswordVar, 'Process')
$scope = 'Process'
if ([string]::IsNullOrEmpty($password)) {
  $password = [Environment]::GetEnvironmentVariable($PasswordVar, 'User')
  $scope = 'User'
}
if ([string]::IsNullOrEmpty($password)) {
  $password = [Environment]::GetEnvironmentVariable($PasswordVar, 'Machine')
  $scope = 'Machine'
}

$fp = Get-Fingerprint $password

if ($FingerprintOnly) {
  Write-Output "PasswordVar=$PasswordVar scope=$scope fingerprint=$fp"
  exit 0
}

if ([string]::IsNullOrEmpty($password)) {
  # This is the one thing the loop stops for. Say exactly what is missing and do not invent
  # a value — a serve started with a guessed password is how the 2026-08-23 accident looked
  # from the inside.
  Write-Error @"
$PasswordVar is not set in the Process, User, or Machine scope.

A real `opencode serve` cannot be started without it, and this script will not invent one.
Set it and re-run:

  `$env:$PasswordVar = '<the password>'
"@
  exit 2
}

$existing = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if ($existing) {
  $pids = ($existing | Select-Object -ExpandProperty OwningProcess -Unique) -join ','
  Write-Output "Port $Port already has a listener (pid $pids)."
  Write-Output "Current environment fingerprint: $fp. Compare against the ALREADY-RUNNING line in $LogPath."
  Write-Output "If they differ, that is the 2026-08-23 accident reproducing — stop and report it."
  Add-Content -Path $LogPath -Encoding UTF8 -Value (
    "$(Get-Date -Format o)`tALREADY-RUNNING`tport=$Port`tpid=$pids`tvar=$PasswordVar`tscope=$scope`tfingerprint=$fp"
  )
  exit 0
}

$logDir = Split-Path -Parent $LogPath
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

$stdoutPath = Join-Path $logDir "serve-$Port-stdout.log"
$stderrPath = Join-Path $logDir "serve-$Port-stderr.log"

$env:OPENCODE_SERVER_PASSWORD = $password
$env:OPENCODE_SERVER_USERNAME = $Username

$proc = Start-Process -FilePath 'cmd.exe' `
  -ArgumentList @('/c', 'opencode', 'serve', '--port', "$Port", '--hostname', '0.0.0.0') `
  -WorkingDirectory $RepoRoot `
  -RedirectStandardOutput $stdoutPath `
  -RedirectStandardError $stderrPath `
  -PassThru `
  -WindowStyle Hidden

Add-Content -Path $LogPath -Encoding UTF8 -Value (
  "$(Get-Date -Format o)`tSTARTED`tport=$Port`tpid=$($proc.Id)`tuser=$Username`tvar=$PasswordVar`tscope=$scope`tfingerprint=$fp`tcwd=$RepoRoot`tby=$env:USERNAME"
)

Write-Output "started opencode serve pid=$($proc.Id) port=$Port fingerprint=$fp"
Write-Output "lifecycle log: $LogPath"

# Wait for the port rather than sleeping a guessed interval: a fixed sleep either wastes time or
# reports a healthy server as dead, and we have no way to tell which afterwards.
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
  $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
  if ($listener) {
    # The pid recorded on the STARTED line is the launcher (`cmd.exe`), NOT the process that
    # ends up owning the socket. On 2026-08-28 the Q6 E2E found the last STARTED line saying
    # pid=56360 while the actual listener was pid=33152 — an unrecorded process — which is the
    # exact shape of the 2026-08-23 accident this script was written to prevent (HANDOFF §0-5).
    # Record the OWNING pid of the listening socket, because that is the process a later reader
    # has to find and stop.
    $listenPids = ($listener | Select-Object -ExpandProperty OwningProcess -Unique) -join ','
    Add-Content -Path $LogPath -Encoding UTF8 -Value (
      "$(Get-Date -Format o)`tLISTENING`tport=$Port`tlauncher_pid=$($proc.Id)`tlisten_pid=$listenPids`tfingerprint=$fp"
    )
    Write-Output "port $Port is listening (owning pid $listenPids; launcher pid $($proc.Id))"
    exit 0
  }
  if ($proc.HasExited) {
    Add-Content -Path $LogPath -Encoding UTF8 -Value (
      "$(Get-Date -Format o)`tDIED-EARLY`tport=$Port`tpid=$($proc.Id)`texit=$($proc.ExitCode)"
    )
    Write-Error "opencode serve exited with $($proc.ExitCode) before listening. See $stderrPath"
    exit 3
  }
  Start-Sleep -Milliseconds 500
}

Add-Content -Path $LogPath -Encoding UTF8 -Value (
  "$(Get-Date -Format o)`tNEVER-LISTENED`tport=$Port`tpid=$($proc.Id)"
)
Write-Error "opencode serve did not listen on $Port within 60s. See $stderrPath"
exit 4
