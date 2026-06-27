#!/usr/bin/env python3
"""Wait for the running 5x5 fragile sweep to finish, then run the equal-weight (Wf=Wv=100)
confirmation on 3 starters. Tests the user's question: if the solver may FREELY swap fragile<->
volunteer (same price), can it escape the healthy=22 ceiling to 23?

Detects sweep completion by: 25 frag-exp versions present (excl. canary 126) OR no java.exe running.
"""
import os, re, subprocess, sys, time, sqlite3

ROOT = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(ROOT, "residency_scheduler.db")


def exp_count():
    c = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
    try:
        return c.execute("SELECT COUNT(*) FROM schedule_versions "
                         "WHERE name LIKE 'frag-exp-wf5-%' OR name LIKE 'frag-exp-wf15-%' "
                         "OR name LIKE 'frag-exp-wf50-%' OR name LIKE 'frag-exp-wf200-%' "
                         "OR name LIKE 'frag-exp-wf500-%'").fetchone()[0]
    finally:
        c.close()


def java_running():
    try:
        out = subprocess.run(["tasklist"], capture_output=True, text=True).stdout.lower()
        return out.count("java.exe")
    except Exception:
        return -1


print(f"[chain] waiting for 5x5 sweep to finish (have {exp_count()}/25)...", flush=True)
while True:
    n = exp_count()
    if n >= 25:
        print(f"[chain] sweep complete ({n}/25).", flush=True)
        break
    if java_running() == 0 and n >= 20:
        print(f"[chain] no java running and {n}/25 done — treating sweep as finished.", flush=True)
        break
    time.sleep(120)

time.sleep(30)  # let the last run flush + tag
print("[chain] launching Wf=100 (=Wv) x3 confirmation ...", flush=True)
rc = subprocess.run([sys.executable, os.path.join(ROOT, "experiment_fragile_tradeoff.py"),
                     "--versions", "7,11,53", "--wf", "100", "--budget", "600"],
                    cwd=ROOT).returncode
print(f"[chain] Wf=100 confirmation done (rc={rc}).", flush=True)
