@echo off
REM ===========================================================================
REM Autonomous solver-sweep launcher (Windows).
REM
REM Runs sweep_driver.py and RELAUNCHES it if it crashes unexpectedly, so a
REM transient Python error self-heals. It does NOT relaunch on an intentional
REM HALT (exit 2) or a refused-start lock (exit 3) -- those need a human.
REM
REM Exit codes from sweep_driver.py:
REM    0 = queue exhausted / clean stop  -> done, do not relaunch
REM    2 = HALT (corruption, external DB lock, failure caps, preflight) -> stop
REM    3 = another driver already running (lockfile) -> stop
REM  other = unexpected crash -> relaunch after a short delay
REM
REM Register for reboot-survival (run ONCE, in an elevated prompt):
REM   schtasks /Create /TN "ResidencySweep" /SC ONLOGON /RL HIGHEST ^
REM     /TR "cmd /c \"cd /d C:\Users\Jarrett\Desktop\Block Schedule app\residency-scheduler\residency-scheduler && run_sweep.cmd\""
REM Remove it with:  schtasks /Delete /TN "ResidencySweep" /F
REM The single-writer lockfile (sweep.lock) prevents the at-logon copy and a
REM manual copy from both running -- the second one refuses to start (exit 3).
REM ===========================================================================
cd /d "%~dp0"

:loop
echo [run_sweep] starting sweep_driver.py at %DATE% %TIME%
python sweep_driver.py
set RC=%ERRORLEVEL%
echo [run_sweep] sweep_driver.py exited with code %RC% at %DATE% %TIME%

if "%RC%"=="0" goto :done
if "%RC%"=="2" goto :done
if "%RC%"=="3" goto :done

echo [run_sweep] unexpected crash (code %RC%); relaunching in 30s...
timeout /t 30 /nobreak >nul
goto :loop

:done
echo [run_sweep] stopping (exit code %RC%).
