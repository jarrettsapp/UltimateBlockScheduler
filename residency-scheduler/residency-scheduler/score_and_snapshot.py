#!/usr/bin/env python3
"""Score the LIVE year-2 schedule and snapshot it as a named version.

Used by the run-solver-config skill after a headless solve finishes. Prints the
standard metrics line and saves a schedule_versions row + assignments.

Usage:
  python score_and_snapshot.py --name "cfgD run1 (OFF/2/120)" \
      --notes "floor OFF, target=2, weight=120; 75min/phase" [--no-save]
Prints: volunteer / fragile / healthy / heavy->heavy / runs>6wk, per-weekend list,
and (if saved) the new version id. Exits 0.
"""
import sqlite3, argparse, re, os, json, subprocess

DB='residency_scheduler.db'
HEAVY={'ICU','VA','Broadlawns','Younker 7 Days','Younker 7 Nights','Younker 8 Pulmonology'}
MEDIUM={'Inpatient GI','Infectious Disease'}
SRC={'Inpatient GI','Infectious Disease','Outpatient GI','Outpatient Pulmonology','Ambulatory A',
     'Emergency Medicine','Addiction Medicine','Elective','Outpatient TIC Cardiology','Outpatient UPH Cardiology'}
# Y7-Days name for the Phase-4 Saturday coverage metric (mirrors WorkloadTiers / SolutionScoreReporter).
Y7_DAYS='Younker 7 Days'


def score_grid(g, cat, ress):
    """Score a schedule grid — the SINGLE source of truth for all primary metrics, shared by the
    live scorer (main) and the seed-vs-final comparison tool. `g` maps resident_id -> list[27] of
    rotation NAMES (1-based block index, index 0 unused, index w and w+1 used). `cat` = set of
    categorical resident ids. `ress` = {resident_id: name}. Returns a dict of metrics + perw/runs6/
    hm_hist. Pure function of the grid — identical math whether the grid came from the live
    `assignments` table, a saved version, or a decoded seed."""
    vol=frag=heal=0; perw=[]
    for w in range(1,26):
        e=sum(1 for rid in cat if g.get(rid,[None]*27)[w] in SRC and g.get(rid,[None]*27)[w] not in HEAVY and g.get(rid,[None]*27)[w+1] not in HEAVY)
        perw.append(e); vol+=e==0;frag+=e==1;heal+=e>1
    over=hh=0; runs6=[]
    hm_stretches=[]          # every H/M run length in weeks, across all categorical residents
    def t(r):return 'HM' if r in HEAVY or r in MEDIUM else 'L'
    for rid in cat:
        gg=g.get(rid,[None]*27);i=1
        while i<=26:
            if t(gg[i])=='HM':
                j=i+1
                while j<=26 and t(gg[j])=='HM':j+=1
                wk=(j-i)*2
                hm_stretches.append(wk)
                if wk>6: over+=1; runs6.append((ress.get(rid,rid),wk))
                i=j
            else:i+=1
        for b in range(1,26):
            if gg[b] in HEAVY and gg[b+1] in HEAVY and gg[b]!=gg[b+1]:hh+=1
    from collections import Counter
    return {
        'volunteer':vol, 'fragile':frag, 'healthy':heal, 'heavy_heavy':hh, 'runs_gt6wk':over,
        'hm_max':max(hm_stretches) if hm_stretches else 0,
        'hm_runs_ge6':sum(1 for w in hm_stretches if w>=6),
        'hm_total':sum(hm_stretches),
        'perw':perw, 'runs6':runs6, 'hm_hist':dict(sorted(Counter(hm_stretches).items())),
    }


def _git_commit():
    try:
        return subprocess.check_output(['git','rev-parse','--short','HEAD'],
                                       stderr=subprocess.DEVNULL).decode().strip()
    except Exception:
        return None


def _parse_solve_log(path):
    """Best-effort extraction of per-phase secs/status, seed_id and tier scores from a solve log.
    Returns a dict; missing fields are None. Never raises — telemetry must not break the snapshot."""
    out={'p0_secs':None,'p1_secs':None,'p2_secs':None,'p3_secs':None,
         'p0_status':None,'p1_status':None,'p2_status':None,'p3_status':None,
         'seed_id':None,'tier1':None,'tier2':None,'tier3':None}
    try:
        txt=open(path,encoding='utf-8',errors='replace').read()
    except Exception:
        return out
    # Phase lines look like: "Phase 1 result: OPTIMAL  tier1_score=0  (75.4s)" — allow arbitrary
    # text (the inline score) between the status word and the trailing "(Ns)".
    for ph in (0,1,2,3):
        m=re.search(rf'Phase {ph} result:\s*(\w+).*?\(([\d.]+)s\)', txt)
        if m:
            out[f'p{ph}_status']=m.group(1); out[f'p{ph}_secs']=float(m.group(2))
    m=re.search(r'seeded from seed_id=([0-9a-f]+)', txt) or re.search(r'replayed seed ([0-9a-f]+)', txt)
    if m: out['seed_id']=m.group(1)
    # Tier scores from the per-phase result lines (authoritative) or the score-report header.
    m=re.search(r'tier1_score=(\d+)', txt) or re.search(r'Tier-1 score:\s*(\d+)', txt)
    if m: out['tier1']=int(m.group(1))
    m=re.search(r'tier2=(\d+)', txt) or re.search(r'Tier-2 cost:\s*(\d+)', txt)
    if m: out['tier2']=int(m.group(1))
    return out


def _read_trajectory(path):
    """Read a SOLVE_TRAJECTORY_CSV (elapsed_s,objective,best_bound,cpsat_wall_s) → list of tuples."""
    pts=[]
    if not path or not os.path.exists(path): return pts
    try:
        import csv as _csv
        for row in _csv.DictReader(open(path)):
            try:
                pts.append((float(row['elapsed_s']),
                            float(row['objective']) if row.get('objective') else None,
                            float(row['best_bound']) if row.get('best_bound') else None,
                            float(row['cpsat_wall_s']) if row.get('cpsat_wall_s') else None))
            except (ValueError,KeyError):
                continue
    except Exception:
        pass
    return pts


def _config_snapshot(c):
    """Full self-describing config snapshot from schedule_config (key/value) as a dict + JSON."""
    snap={}
    try:
        for k,v in c.execute('SELECT config_key,config_value FROM schedule_config'):
            snap[k]=v
    except sqlite3.OperationalError:
        pass
    return snap


def _saturday_y7(g, cat, rots_by_name):
    """Phase-4 new metric: count of blocks (Saturdays) with >=1 Younker-7-Days resident. -1 if N/A."""
    if Y7_DAYS not in set(rots_by_name): return -1
    covered=0
    for w in range(1,26):
        if any(g.get(rid,[None]*27)[w]==Y7_DAYS for rid in cat): covered+=1
    return covered


def _write_solve_run(c, a, vol, frag, heal, hh, over, perw, sat, version_id,
                     hm_max=None, hm_runs_ge6=None, hm_total=None):
    """Populate the solve_runs family. Best-effort; gated on the new tables existing.
    Returns the new run_id or None. Caller wraps in try/except + commit."""
    # Tables may not be migrated yet (seed-gen still running) — fail soft.
    try:
        c.execute('SELECT 1 FROM solve_runs LIMIT 1')
    except sqlite3.OperationalError:
        print('solve_runs table not present yet — skipping rich telemetry (run after migration).')
        return None
    log=_parse_solve_log(a.solve_log) if a.solve_log else {}
    snap=_config_snapshot(c)
    cfg_json=json.dumps(snap, sort_keys=True)
    cfg_hash=str(abs(hash(cfg_json)) % (10**12))
    traj=_read_trajectory(a.traj_csv)
    # ── 3-state intake status + ranking objective (schedule-search-plan methodology) ──
    # run_status is derived from the Phase-3 result status in the solve log; final_objective is the
    # last trajectory incumbent (NULL — never 0 — when Phase 3 produced no incumbent / fell back).
    p3s=(log.get('p3_status') or '').upper()
    if p3s=='OPTIMAL':
        run_status='PHASE3_OPTIMAL'
    elif p3s=='FEASIBLE':
        run_status='PHASE3_FEASIBLE'
    elif p3s in ('UNKNOWN','MODEL_INVALID','INFEASIBLE',''):
        # Phase 3 found no committable incumbent → the live schedule is the Phase-2 fallback.
        run_status='PHASE2_FALLBACK'
    else:
        run_status='PHASE3_'+p3s
    # final_objective = last trajectory objective if Phase 3 actually optimized; else NULL.
    obj_pts=[o for (_e,o,_bb,_w) in traj if o is not None]
    final_objective = obj_pts[-1] if (obj_pts and run_status in ('PHASE3_FEASIBLE','PHASE3_OPTIMAL')) else None
    feasible = 0 if run_status=='PHASE2_FALLBACK' else 1
    rid=c.execute(
        "INSERT INTO solve_runs(year,run_at,git_commit,data_epoch,backfilled,config_label,config_hash,"
        "config_json,seed_id,seed_select_mode,worker_count,p0_secs,p1_secs,p2_secs,p3_secs,"
        "p0_status,p1_status,p2_status,p3_status,tier1_score,tier2_score,tier3_score,feasible,"
        "run_status,final_objective,version_id) "
        "VALUES(?,datetime('now'),?,?,0,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (a.year,_git_commit(),a.data_epoch,a.config_label,cfg_hash,cfg_json,
         log.get('seed_id'),os.environ.get('PHASE0_SEED_SELECT'),
         (int(snap['cpsat_num_workers']) if snap.get('cpsat_num_workers','').isdigit() else None),
         log.get('p0_secs'),log.get('p1_secs'),log.get('p2_secs'),log.get('p3_secs'),
         log.get('p0_status'),log.get('p1_status'),log.get('p2_status'),log.get('p3_status'),
         log.get('tier1'),log.get('tier2'),log.get('tier3'),feasible,
         run_status,final_objective,version_id)
    ).lastrowid
    c.execute(
        "INSERT OR REPLACE INTO solve_run_metrics(run_id,volunteer,fragile,healthy,heavy_heavy,"
        "runs_gt6wk,saturday_coverage,hm_max_stretch,hm_runs_ge6wk,hm_total_wk) "
        "VALUES(?,?,?,?,?,?,?,?,?,?)",
        (rid,vol,frag,heal,hh,over,sat,hm_max,hm_runs_ge6,hm_total))
    c.executemany("INSERT OR REPLACE INTO solve_run_weekend(run_id,weekend_index,coverers) VALUES(?,?,?)",
                  [(rid,i,cov) for i,cov in enumerate(perw)])
    if traj:
        c.executemany("INSERT OR REPLACE INTO solve_run_trajectory(run_id,elapsed_s,objective,"
                      "best_bound,cpsat_wall_s) VALUES(?,?,?,?,?)",
                      [(rid,e,o,bb,w) for (e,o,bb,w) in traj])
    return rid

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--name',required=True)
    ap.add_argument('--notes',default='')
    ap.add_argument('--year',type=int,default=2)
    ap.add_argument('--no-save',action='store_true')
    # ── Rich real-solve telemetry (solve_runs family). All optional; absent ⇒ legacy behavior. ──
    ap.add_argument('--config-label',default=None,help='config_label for the solve_runs row (e.g. cfgR6-03)')
    ap.add_argument('--data-epoch',default='post_fix_seeded',help='separation key; pre-I1 runs use a historical epoch')
    ap.add_argument('--solve-log',default=None,help='path to the solve log (per-phase secs/status, seed_id, Tier-1)')
    ap.add_argument('--traj-csv',default=None,help='path to the SOLVE_TRAJECTORY_CSV for this run')
    a=ap.parse_args()
    db=sqlite3.connect(DB); c=db.cursor()
    rots={r[0]:r[1] for r in c.execute('SELECT id,name FROM rotations')}
    ress={r[0]:r[1] for r in c.execute('SELECT id,name FROM residents WHERE is_auxiliary=0')}
    cat=set(ress)
    yb={r[0]:r[1] for r in c.execute('SELECT id,block_number FROM blocks WHERE schedule_year=?',(a.year,))}
    g={}
    for rid,rotid,bid in c.execute('SELECT resident_id,rotation_id,block_id FROM assignments WHERE block_id IN (SELECT id FROM blocks WHERE schedule_year=?)',(a.year,)):
        bn=yb.get(bid)
        if bn:
            g.setdefault(rid,[None]*27)[bn]=rots.get(rotid)
    # Score via the single shared definition (also used by compare_seed_vs_final.py).
    sc=score_grid(g, cat, ress)
    vol,frag,heal,hh,over=sc['volunteer'],sc['fragile'],sc['healthy'],sc['heavy_heavy'],sc['runs_gt6wk']
    perw=sc['perw']; hm_max=sc['hm_max']; hm_runs_ge6=sc['hm_runs_ge6']; hm_total=sc['hm_total']
    print(f'volunteer={vol}  fragile={frag}  healthy={heal}  heavy->heavy={hh}  runs>6wk={over}')
    print(f'hm_max_stretch={hm_max}wk  hm_runs>=6wk={hm_runs_ge6}  hm_total_wk={hm_total}')
    print(f'per-weekend coverers: {perw}')
    print(f'runs>6wk detail: {sc["runs6"]}')
    print(f'hm stretch histogram (weeks:count): {sc["hm_hist"]}')
    if not a.no_save:
        # snapshot LIVE year into a new version (categoricals only, matching prior versions)
        nid=c.execute('SELECT COALESCE(MAX(id),0)+1 FROM schedule_versions').fetchone()[0]
        summary=f'[scored] vol={vol} frag={frag} heal={heal} hh={hh} runs6={over}'
        c.execute("""INSERT INTO schedule_versions(id,schedule_year,name,created_at,notes,tier1_score,tier2_score,feasible,summary)
                     VALUES(?,?,?,datetime('now'),?,0,0,1,?)""",(nid,a.year,a.name,a.notes,summary))
        # save ALL year residents (categorical + aux) to match the 348-row convention
        allrows=[]
        for rid,rotid,bid in c.execute('SELECT resident_id,rotation_id,block_id FROM assignments WHERE block_id IN (SELECT id FROM blocks WHERE schedule_year=?)',(a.year,)):
            bn=yb.get(bid)
            if bn: allrows.append((nid,rid,rotid,bn))
        c.executemany('INSERT INTO schedule_version_assignments(version_id,resident_id,rotation_id,block_number) VALUES(?,?,?,?)',allrows)
        db.commit()
        print(f'SAVED_VERSION_ID={nid}  rows={len(allrows)}')
        print('integrity:', c.execute('PRAGMA integrity_check').fetchone()[0][:30])
        # ── Rich per-run telemetry: one durable solve_runs row + metrics/weekend/trajectory. ──
        # Best-effort; never breaks the snapshot. Skipped automatically if the tables aren't
        # migrated yet (seed-gen still running) — the legacy version save above is unaffected.
        try:
            sat=_saturday_y7(g, cat, set(rots.values()))
            run_id=_write_solve_run(c, a, vol, frag, heal, hh, over, perw, sat, nid,
                                    hm_max=hm_max, hm_runs_ge6=hm_runs_ge6, hm_total=hm_total)
            if run_id is not None:
                db.commit()
                print(f'SOLVE_RUN_ID={run_id}  (epoch={a.data_epoch} label={a.config_label} saturday_y7={sat})')
        except Exception as ex:
            db.rollback()
            print(f'solve_runs telemetry skipped (non-fatal): {ex}')

if __name__=='__main__':
    main()
