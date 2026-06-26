<#
=============================================================================
 launch_pipeline.ps1  --  ONE-COMMAND end-to-end pipeline launcher.

 PURPOSE: give any operator (including Haiku) a single paste-and-go command
 to launch the full pipeline unattended. Zero decisions, zero shell knowledge
 required. Just paste this and walk away:

     powershell -ExecutionPolicy Bypass -File `
       "C:\Users\Jarrett\Desktop\Block Schedule app\residency-scheduler\residency-scheduler\launch_pipeline.ps1"

 Or with custom arguments:

     powershell -ExecutionPolicy Bypass -File "...launch_pipeline.ps1" `
       -Seeds 30 -Harvest 60 -TopK 15 -P3Budget 600

 WHAT IT DOES (in order):
   1. Moves to the project dir by ABSOLUTE path (never guesses).
   2. Refuses double-launch: if pipeline.lock exists AND its PID is alive,
      reports the already-running pipeline and exits 0 (idempotent).
   3. Checks that the JavaFX app is CLOSED (it locks the DB).
   4. Launches pipeline_driver.py --detach so it survives this shell closing.
   5. Waits ~60s, runs health checks, prints a clean verdict.

 PARAMETERS:
   -Seeds     N   Phase-0 seed pool target (default: 20)
   -Harvest   M   Phase-1/2 harvest runs (default: 40)
   -TopK      K   Top-K versions to optimize in Phase-3 (default: 10)
   -P3Budget  S   Timefold seconds per Phase-3 run (default: 300)
   -DryRun        Pass --dry-run to the driver (prints what would run; no java)
   -SkipSeeds     Skip Stage 1 (pool already populated)
   -SkipHarvest   Skip Stage 2 (harvest versions already in DB)

 EXIT CODES:
   0 = pipeline is running (freshly launched or already was)
   1 = launch attempted but driver not running (see log tail)
=============================================================================
#>
param(
    [int]    $Seeds       = 20,
    [int]    $Harvest     = 40,
    [int]    $TopK        = 10,
    [int]    $P3Budget    = 300,
    [switch] $DryRun,
    [switch] $SkipSeeds,
    [switch] $SkipHarvest
)
$ErrorActionPreference = 'Stop'

# --- 1. Absolute project dir --------------------------------------------------
$Root      = 'C:\Users\Jarrett\Desktop\Block Schedule app\residency-scheduler\residency-scheduler'
Set-Location -LiteralPath $Root

$Lock      = Join-Path $Root 'pipeline.lock'
$StatusMd  = Join-Path $Root 'PIPELINE_STATUS.md'
$ReportMd  = Join-Path $Root 'PIPELINE_REPORT.md'
$ResultCsv = Join-Path $Root 'pipeline_results.csv'
$Driver    = Join-Path $Root 'pipeline_driver.py'
$OutLog    = Join-Path $Root 'pipeline_driver.out.log'
$ErrLog    = Join-Path $Root 'pipeline_driver.err.log'

function Get-LivePidFromLock {
    if (-not (Test-Path -LiteralPath $Lock)) { return $null }
    $raw = (Get-Content -LiteralPath $Lock -Raw -ErrorAction SilentlyContinue)
    if (-not $raw) { return $null }
    $pidNum = 0
    if (-not [int]::TryParse($raw.Trim(), [ref]$pidNum)) { return $null }
    $proc = Get-Process -Id $pidNum -ErrorAction SilentlyContinue
    if ($proc) { return $pidNum } else { return $null }
}

function Get-LiveJavaPid {
    # Look for a java process launched by the pipeline (HeadlessSolveRunner or TimefoldOptimizeRunner)
    $procs = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            $_.CommandLine -and (
                $_.CommandLine -match 'HeadlessSolveRunner' -or
                $_.CommandLine -match 'TimefoldOptimizeRunner'
            )
        })
    if ($procs.Count -gt 0) { return $procs[0].ProcessId } else { return $null }
}

# --- 2. Idempotent check -------------------------------------------------------
$alivePid = Get-LivePidFromLock
if ($alivePid) {
    $javaPid  = Get-LiveJavaPid
    $javaTxt  = if ($javaPid) { "java PID $javaPid is doing work" } else { "between stages / mid-startup" }
    Write-Host "[launch_pipeline] ALREADY RUNNING -- driver PID $alivePid; $javaTxt. Nothing to do." -ForegroundColor Green
    Write-Host "[launch_pipeline] Progress: PIPELINE_STATUS.md / PIPELINE_REPORT.md / pipeline_results.csv"
    exit 0
}

# --- 3. DB-lock guard ---------------------------------------------------------
$javaApp = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and ($_.CommandLine -match 'com\.residency\.ui\.MainApp' -or $_.CommandLine -match 'javafx:run') })
if ($javaApp.Count -gt 0) {
    Write-Host "[launch_pipeline] BLOCKED -- the JavaFX scheduling app is OPEN and locks the database." -ForegroundColor Red
    foreach ($p in $javaApp) {
        Write-Host ("    PID {0}: {1}" -f $p.ProcessId, $p.CommandLine) -ForegroundColor Red
    }
    Write-Host "[launch_pipeline] CLOSE the app, then run this command again." -ForegroundColor Yellow
    exit 1
}

if (Test-Path -LiteralPath $Lock) {
    Write-Host "[launch_pipeline] Note: stale pipeline.lock (no live PID). Driver will reclaim it." -ForegroundColor Yellow
}

if (-not (Test-Path -LiteralPath $Driver)) {
    Write-Host "[launch_pipeline] FATAL: pipeline_driver.py not found at $Driver" -ForegroundColor Red
    exit 1
}

# --- 4. Build argument list ---------------------------------------------------
$driverArgs = @(
    "`"$Driver`"",
    '--seeds',     "$Seeds",
    '--harvest',   "$Harvest",
    '--top-k',     "$TopK",
    '--p3-budget', "$P3Budget",
    '--detach'
)
if ($DryRun)      { $driverArgs += '--dry-run'      }
if ($SkipSeeds)   { $driverArgs += '--skip-seeds'   }
if ($SkipHarvest) { $driverArgs += '--skip-harvest' }

# Print the effective configuration before launching
Write-Host ""
Write-Host "================ PIPELINE CONFIGURATION ================"
Write-Host ("  Seeds (pool target):   {0}" -f $Seeds)
Write-Host ("  Harvest runs:          {0}" -f $Harvest)
Write-Host ("  Top-K for Phase-3:     {0}" -f $TopK)
Write-Host ("  Timefold budget:       {0}s per run" -f $P3Budget)
$skipTxt = @()
if ($SkipSeeds)   { $skipTxt += 'Stage 1 (seeds)' }
if ($SkipHarvest) { $skipTxt += 'Stage 2 (harvest)' }
if ($skipTxt.Count -gt 0) { Write-Host ("  Skipping:              {0}" -f ($skipTxt -join ', ')) }
if ($DryRun) { Write-Host "  Mode: DRY RUN (no java, no DB writes)" -ForegroundColor Yellow }

# Estimate total wall time
$seedTimeSec    = if ($SkipSeeds)   { 0 } else { [int]($Seeds * 15) }      # ~15s per seed
$harvestTimeSec = if ($SkipHarvest) { 0 } else { [int]($Harvest * 120) }   # ~2min per harvest
$p3TimeSec      = [int]($TopK * ($P3Budget + 120))                          # budget + overhead
$totalMin       = [int](($seedTimeSec + $harvestTimeSec + $p3TimeSec) / 60)
Write-Host ("  Estimated total time:  ~{0} minutes ({1}h {2}m)" -f $totalMin, [int]($totalMin/60), ($totalMin % 60))
Write-Host "========================================================="
Write-Host ""

# --- 5. Launch ----------------------------------------------------------------
Write-Host "[launch_pipeline] Launching pipeline_driver.py --detach ..."
$started = Start-Process -FilePath 'python' `
    -ArgumentList $driverArgs `
    -WorkingDirectory $Root `
    -WindowStyle Hidden `
    -PassThru

Write-Host "[launch_pipeline] Spawned driver PID $($started.Id). Waiting for it to take the lock ..."

# --- 6. Health checks ---------------------------------------------------------
$alivePid = $null
$javaPid  = $null
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 1
    if (-not $alivePid) { $alivePid = Get-LivePidFromLock }
    if ($alivePid -and -not $javaPid) { $javaPid = Get-LiveJavaPid }
    if ($alivePid -and $javaPid) { break }
    if ($started.HasExited -and $started.ExitCode -ne 0 -and -not $alivePid) { break }
}

Write-Host ""
Write-Host "================ PIPELINE HEALTH CHECK ================="

$pidOk    = [bool]$alivePid
$javaOk   = [bool]$javaPid
$lockOk   = Test-Path -LiteralPath $Lock
$statusOk = Test-Path -LiteralPath $StatusMd

function Mark($ok) { if ($ok) { 'OK ' } else { 'XX ' } }
Write-Host ("  [{0}] driver PID alive (holds lock): {1}" -f (Mark $pidOk),  ($(if($pidOk){$alivePid}else{'NONE'})))
Write-Host ("  [{0}] java solve PID alive (doing work): {1}" -f (Mark $javaOk), ($(if($javaOk){$javaPid}else{'NONE — may still be starting up'})))
Write-Host ("  [{0}] pipeline.lock present"      -f (Mark $lockOk))
Write-Host ("  [{0}] PIPELINE_STATUS.md present" -f (Mark $statusOk))
Write-Host "========================================================="

if ($pidOk) {
    if ($javaOk) {
        Write-Host "[launch_pipeline] RUNNING. Driver PID $alivePid + java PID $javaPid are active." -ForegroundColor Green
    } else {
        Write-Host "[launch_pipeline] STARTING. Driver PID $alivePid is up; java solve not yet spawned." -ForegroundColor Yellow
        Write-Host "[launch_pipeline] Stage 1 (seed gen) runs fast. Re-run this script in ~30s to confirm." -ForegroundColor Yellow
    }
    Write-Host ""
    Write-Host "[launch_pipeline] Walk away. Progress files (check these anytime):"
    Write-Host "    PIPELINE_STATUS.md   -- current stage and ETA"
    Write-Host "    PIPELINE_REPORT.md   -- cumulative results (written throughout + final)"
    Write-Host "    pipeline_results.csv -- raw run-by-run log"
    Write-Host "    pipeline_driver.out.log -- full driver stdout"
    Write-Host ""
    Write-Host "[launch_pipeline] If something fails, the driver writes the reason to:"
    Write-Host "    PIPELINE_STATUS.md (top of file shows HALTED + reason)"
    Write-Host "    pipeline_driver.out.log (full stack trace)"
    exit 0
} else {
    Write-Host "[launch_pipeline] NOT RUNNING -- driver exited during startup (likely a preflight HALT)." -ForegroundColor Red
    Write-Host "[launch_pipeline] Most common causes: wrong git branch, missing cp.txt, DB integrity error," -ForegroundColor Red
    Write-Host "[launch_pipeline] or JavaFX app still open." -ForegroundColor Red
    Write-Host "----- pipeline_driver.out.log (tail) -----"
    if (Test-Path -LiteralPath $OutLog) { Get-Content -LiteralPath $OutLog -Tail 30 }
    Write-Host "----- pipeline_driver.err.log (tail) -----"
    if (Test-Path -LiteralPath $ErrLog) { Get-Content -LiteralPath $ErrLog -Tail 30 }
    Write-Host "-------------------------------------------"
    exit 1
}
