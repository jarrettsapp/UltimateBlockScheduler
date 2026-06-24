<#
=============================================================================
 launch_sweep.ps1  --  THE canonical, paste-and-go sweep launcher (Windows).

 PURPOSE: collapse "start the autonomous sweep" into ONE command that makes
 ZERO shell decisions for the operator (Sonnet/Haiku). No `cd`, no `cmd /d`,
 no figuring out redirection, no Bash-vs-PowerShell. Just:

     powershell -ExecutionPolicy Bypass -File `
       "C:\Users\Jarrett\Desktop\Block Schedule app\residency-scheduler\residency-scheduler\launch_sweep.ps1"

 WHAT IT DOES (in order):
   1. Moves to the project dir by ABSOLUTE path (hard-coded; never relies on
      the caller's working directory).
   2. Refuses to double-launch: if sweep.lock exists AND its PID is alive, it
      reports the already-running sweep and exits 0 (idempotent -- safe to
      re-run "just in case").
   3. Launches sweep_driver.py DETACHED via Start-Process so it survives this
      shell/session closing (desktop is rebooted manually by the owner, so no
      Task Scheduler is needed). stdout+stderr go to launch_sweep.out.log.
   4. Waits briefly, then runs the 4 health checks and prints a clean verdict:
        - driver PID alive?
        - sweep.lock present + fresh?
        - SWEEP_STATUS.md present?
        - sweep_results.csv present?
      If the driver already exited (a HALT from its own preflight, e.g. wrong
      git branch / missing cp.txt / DB integrity), it prints the log tail so
      the reason is visible -- and exits non-zero.

 The driver owns ALL real gatekeeping (git branch, cp.txt, trajectory class,
 DB integrity, single-writer lock) in its own preflight(). This script does
 NOT duplicate that -- it just launches and reads back the result, so the two
 can never drift apart.

 Exit codes from THIS script:
   0 = sweep is running (freshly launched, or already was)
   1 = launch attempted but the driver is not running (see printed log tail)
=============================================================================
#>
$ErrorActionPreference = 'Stop'

# --- 1. Absolute project dir (the one thing that must never be guessed) ------
$Root = 'C:\Users\Jarrett\Desktop\Block Schedule app\residency-scheduler\residency-scheduler'
Set-Location -LiteralPath $Root

$Lock      = Join-Path $Root 'sweep.lock'
$StatusMd  = Join-Path $Root 'SWEEP_STATUS.md'
$ResultsCsv= Join-Path $Root 'sweep_results.csv'
$Driver    = Join-Path $Root 'sweep_driver.py'
$State     = Join-Path $Root 'sweep_state.json'
# Driver-owned logs (the --detach respawn writes these). The launcher only READS
# them for diagnostics; it never opens them for write (that caused the
# PermissionError / zombie-conhost lock on 2026-06-23).
$OutLog    = Join-Path $Root 'sweep_driver.out.log'
$ErrLog    = Join-Path $Root 'sweep_driver.err.log'

function Get-LivePidFromLock {
    if (-not (Test-Path -LiteralPath $Lock)) { return $null }
    $raw = (Get-Content -LiteralPath $Lock -Raw -ErrorAction SilentlyContinue)
    if (-not $raw) { return $null }
    $pidNum = 0
    if (-not [int]::TryParse($raw.Trim(), [ref]$pidNum)) { return $null }
    $proc = Get-Process -Id $pidNum -ErrorAction SilentlyContinue
    if ($proc) { return $pidNum } else { return $null }
}

# Return the java solve PID the driver recorded for the currently-RUNNING unit,
# but only if that process is actually alive. This is the check that was missing
# on 2026-06-23 11:32: the driver's status said RUNNING while the java solve had
# silently died at Phase 0, so "walk away" walked away from a corpse. A live
# child java PID (RAM climbing) is the real proof the sweep is doing work.
function Get-LiveSolvePid {
    if (-not (Test-Path -LiteralPath $State)) { return $null }
    try   { $st = Get-Content -LiteralPath $State -Raw | ConvertFrom-Json }
    catch { return $null }
    foreach ($p in $st.runs.PSObject.Properties) {
        if ($p.Value.status -eq 'RUNNING' -and $p.Value.pid) {
            $jp = 0
            if ([int]::TryParse([string]$p.Value.pid, [ref]$jp)) {
                if (Get-Process -Id $jp -ErrorAction SilentlyContinue) { return $jp }
            }
        }
    }
    return $null
}

# --- 2. Idempotent: already running? ----------------------------------------
$alivePid = Get-LivePidFromLock
if ($alivePid) {
    $solvePid = Get-LiveSolvePid
    $solveTxt = if ($solvePid) { "java solve PID $solvePid is doing work" } else { "between runs / mid-startup (no live java solve right now)" }
    Write-Host "[launch_sweep] ALREADY RUNNING -- driver PID $alivePid holds sweep.lock; $solveTxt. Nothing to do." -ForegroundColor Green
    Write-Host "[launch_sweep] Check progress: SWEEP_STATUS.md / sweep_results.csv"
    exit 0
}

# --- 2b. DB-lock guard: the JavaFX app must be CLOSED -------------------------
# The JavaFX scheduling app (com.residency.ui.MainApp, usually launched via
# `mvn javafx:run`) holds an exclusive lock on residency_scheduler.db. A sweep
# solve that can't get the DB lock dies at startup with a frozen [0s] log. This
# killed the 2026-06-23 12:30 launch. Refuse to launch while it's open, with a
# clear instruction -- never silently start a sweep that's doomed to die.
$javaApp = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and ($_.CommandLine -match 'com\.residency\.ui\.MainApp' -or $_.CommandLine -match 'javafx:run') })
if ($javaApp.Count -gt 0) {
    Write-Host "[launch_sweep] BLOCKED -- the JavaFX scheduling app is OPEN and locks the database." -ForegroundColor Red
    foreach ($p in $javaApp) {
        $what = if ($p.CommandLine -match 'javafx:run') { 'mvn javafx:run (app launcher)' } else { 'MainApp (the app window)' }
        Write-Host ("    PID {0}: {1}" -f $p.ProcessId, $what) -ForegroundColor Red
    }
    Write-Host "[launch_sweep] CLOSE the JavaFX app window, then run this same command again." -ForegroundColor Yellow
    Write-Host "[launch_sweep] (A sweep cannot share the DB with the app -- it would die at Phase 0.)" -ForegroundColor Yellow
    exit 1
}

# Stale lock (no live PID) -- the driver cleans its own lock on clean exit, but
# a hard kill can leave one behind. The driver's preflight tolerates a stale
# lock; we just note it so the operator isn't surprised.
if (Test-Path -LiteralPath $Lock) {
    Write-Host "[launch_sweep] Note: stale sweep.lock present (no live PID). The driver will reclaim it." -ForegroundColor Yellow
}

if (-not (Test-Path -LiteralPath $Driver)) {
    Write-Host "[launch_sweep] FATAL: sweep_driver.py not found at $Driver" -ForegroundColor Red
    exit 1
}

# --- 3. Launch (driver detaches itself via --detach) -------------------------
# CRITICAL LESSON (2026-06-23): the repeated sweep deaths ~100-200s after launch
# were NOT a launch/detachment problem at all -- they were a BUG in the driver's
# pid_alive() (os.kill(pid,0) raising SystemError on Windows, uncaught, crashing
# the wait-loop). That is fixed in sweep_driver.py. Detachment is handled by
# `sweep_driver.py --detach`, which re-spawns itself with Windows
# DETACHED_PROCESS|CREATE_NEW_PROCESS_GROUP|CREATE_BREAKAWAY_FROM_JOB so it
# survives this shell, then writes its OWN logs (sweep_driver.out/err.log).
#
# So the launcher just fires `python --detach`, hidden, and watches briefly.
# NOTE: the driver path has a space ("Block Schedule app"); each -ArgumentList
# token must be individually quoted or python receives only "...\Desktop\Block".
Write-Host "[launch_sweep] Launching sweep_driver.py --detach (self-detaching) ..."
# IMPORTANT: do NOT use -RedirectStandardOutput here. The driver's --detach
# respawn opens its own log files; if Start-Process also holds a log file open
# via redirection, the respawn hits PermissionError and dies silently (this
# masked early PowerShell launches on 2026-06-23 with empty logs). The
# short-lived `python --detach` parent prints only a one-line respawn notice,
# which we don't need to capture.
$started = Start-Process -FilePath 'python' `
    -ArgumentList @("`"$Driver`"", '--detach') `
    -WorkingDirectory $Root `
    -WindowStyle Hidden `
    -PassThru

Write-Host "[launch_sweep] Spawned driver PID $($started.Id). Waiting for it to take the lock + start a solve ..."

# --- 4. Health checks --------------------------------------------------------
# Poll up to ~75s: the driver must take the lock AND spawn its java solve. The
# java spawn lags the lock by a few seconds (DB backup + classpath build first),
# so we keep polling for the solve PID even after the lock appears.
$alivePid  = $null
$solvePid  = $null
for ($i = 0; $i -lt 75; $i++) {
    Start-Sleep -Seconds 1
    if (-not $alivePid) { $alivePid = Get-LivePidFromLock }
    if ($alivePid -and -not $solvePid) { $solvePid = Get-LiveSolvePid }
    if ($alivePid -and $solvePid) { break }
    # The `python --detach` parent re-spawns a detached child and exits 0 almost
    # immediately -- that's success, not failure. Only treat a NON-zero parent
    # exit (with no lock yet) as an early death worth bailing on.
    if ($started.HasExited -and $started.ExitCode -ne 0 -and -not $alivePid) { break }
}

Write-Host ""
Write-Host "================ SWEEP HEALTH CHECK ================"

$pidOk     = [bool]$alivePid
$solveOk   = [bool]$solvePid
$lockOk    = Test-Path -LiteralPath $Lock
$statusOk  = Test-Path -LiteralPath $StatusMd
$resultsOk = Test-Path -LiteralPath $ResultsCsv

function Mark($ok) { if ($ok) { 'OK ' } else { 'XX ' } }
Write-Host ("  [{0}] driver PID alive (holds lock): {1}" -f (Mark $pidOk),    ($(if($pidOk){$alivePid}else{'NONE'})))
Write-Host ("  [{0}] java solve PID alive (doing work): {1}" -f (Mark $solveOk), ($(if($solveOk){$solvePid}else{'NONE'})))
Write-Host ("  [{0}] sweep.lock present"             -f (Mark $lockOk))
Write-Host ("  [{0}] SWEEP_STATUS.md present"        -f (Mark $statusOk))
Write-Host ("  [{0}] sweep_results.csv present"      -f (Mark $resultsOk))
Write-Host "==================================================="

if ($pidOk -and $solveOk) {
    Write-Host "[launch_sweep] RUNNING. Driver PID $alivePid + java solve PID $solvePid are doing work." -ForegroundColor Green
    Write-Host "[launch_sweep] Walk away. Progress lives in SWEEP_STATUS.md and sweep_results.csv."
    Write-Host "[launch_sweep] Driver stdout/stderr: sweep_driver.out.log / sweep_driver.err.log."
    exit 0
}
elseif ($pidOk -and -not $solveOk) {
    # Driver alive but no java yet: usually just slow to spawn (DB backup /
    # classpath build). Report as PROVISIONAL so the operator re-checks rather
    # than walking away on a maybe-stalled launch.
    Write-Host "[launch_sweep] PROVISIONAL: driver PID $alivePid is up but no live java solve yet." -ForegroundColor Yellow
    Write-Host "[launch_sweep] Usually it is mid-startup (DB backup / classpath build). Re-run this" -ForegroundColor Yellow
    Write-Host "[launch_sweep] script in ~30s -- it will say ALREADY RUNNING and show the java PID." -ForegroundColor Yellow
    Write-Host "----- sweep_driver.out.log (tail) -----"
    if (Test-Path -LiteralPath $OutLog) { Get-Content -LiteralPath $OutLog -Tail 12 }
    Write-Host "---------------------------------------"
    exit 0
}
else {
    Write-Host "[launch_sweep] NOT RUNNING -- the driver exited during startup (likely a preflight HALT)." -ForegroundColor Red
    Write-Host "[launch_sweep] Most common cause: wrong git branch, missing cp.txt, or DB integrity." -ForegroundColor Red
    Write-Host "----- sweep_driver.out.log (tail) -----"
    if (Test-Path -LiteralPath $OutLog) { Get-Content -LiteralPath $OutLog -Tail 30 }
    Write-Host "----- sweep_driver.err.log (tail) -----"
    if (Test-Path -LiteralPath $ErrLog) { Get-Content -LiteralPath $ErrLog -Tail 30 }
    Write-Host "---------------------------------------"
    exit 1
}
