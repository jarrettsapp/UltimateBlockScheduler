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
import sqlite3, argparse

DB='residency_scheduler.db'
HEAVY={'ICU','VA','Broadlawns','Younker 7 Days','Younker 7 Nights','Younker 8 Pulmonology'}
MEDIUM={'Inpatient GI','Infectious Disease'}
SRC={'Inpatient GI','Infectious Disease','Outpatient GI','Outpatient Pulmonology','Ambulatory A',
     'Emergency Medicine','Addiction Medicine','Elective','Outpatient TIC Cardiology','Outpatient UPH Cardiology'}

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--name',required=True)
    ap.add_argument('--notes',default='')
    ap.add_argument('--year',type=int,default=2)
    ap.add_argument('--no-save',action='store_true')
    a=ap.parse_args()
    db=sqlite3.connect(DB); c=db.cursor()
    rots={r[0]:r[1] for r in c.execute('SELECT id,name FROM rotations')}
    ress={r[0]:r[1] for r in c.execute('SELECT id,name FROM residents WHERE is_auxiliary=0')}
    cat=set(ress)
    yb={r[0]:r[1] for r in c.execute('SELECT id,block_number FROM blocks WHERE schedule_year=?',(a.year,))}
    g={}
    live_rows=[]
    for rid,rotid,bid in c.execute('SELECT resident_id,rotation_id,block_id FROM assignments WHERE block_id IN (SELECT id FROM blocks WHERE schedule_year=?)',(a.year,)):
        bn=yb.get(bid)
        if bn:
            g.setdefault(rid,[None]*27)[bn]=rots.get(rotid)
            if rid in cat: live_rows.append((rid,rotid,bn))
    vol=frag=heal=0; perw=[]
    for w in range(1,26):
        e=sum(1 for rid in cat if g.get(rid,[None]*27)[w] in SRC and g.get(rid,[None]*27)[w] not in HEAVY and g.get(rid,[None]*27)[w+1] not in HEAVY)
        perw.append(e); vol+=e==0;frag+=e==1;heal+=e>1
    over=hh=0; runs6=[]
    def t(r):return 'HM' if r in HEAVY or r in MEDIUM else 'L'
    for rid in cat:
        gg=g.get(rid,[None]*27);i=1
        while i<=26:
            if t(gg[i])=='HM':
                j=i+1
                while j<=26 and t(gg[j])=='HM':j+=1
                if (j-i)*2>6: over+=1; runs6.append((ress[rid],(j-i)*2))
                i=j
            else:i+=1
        for b in range(1,26):
            if gg[b] in HEAVY and gg[b+1] in HEAVY and gg[b]!=gg[b+1]:hh+=1
    print(f'volunteer={vol}  fragile={frag}  healthy={heal}  heavy->heavy={hh}  runs>6wk={over}')
    print(f'per-weekend coverers: {perw}')
    print(f'runs>6wk detail: {runs6}')
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

if __name__=='__main__':
    main()
