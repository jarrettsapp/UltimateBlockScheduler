#!/usr/bin/env bash
# Safe parallel solver runs via per-slot DB isolation.
# Each run executes in runs/<label>/ against its OWN copy of the master DB, so
# concurrent solves never write the same file. Results merge back into the master
# SERIALLY (one at a time) as schedule_versions.
#
# Subcommands:
#   setup  <label> <floor true|false> <target> <weight>   # copy DB + write config into the COPY
#   launch <label> <p0> <p1> <p2> <p3>                     # start headless solve in the slot (background)
#   status <label>                                         # show phase / alive
#   merge  <label> "<version name>" "<notes>"             # score the slot DB + snapshot into MASTER
#   clean  <label>                                         # delete the slot dir
#
# Master DB integrity is checked before copy and the slot DB before merge.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MASTER="$ROOT/residency_scheduler.db"
WINCLASSES="$(cygpath -w "$ROOT/target/classes")"
CPFILE="$ROOT/cp.txt"
HEAVY='1,3,6,11,14,16'; SRC='19,15,2,4,8,9,13,17,18,21'

die(){ echo "ERROR: $*" >&2; exit 1; }
slotdir(){ echo "$ROOT/runs/$1"; }

integrity(){ python -c "import sqlite3,sys;print(sqlite3.connect(sys.argv[1]).execute('PRAGMA integrity_check').fetchone()[0])" "$1"; }

cmd_setup(){
  local label="$1" floor="$2" target="$3" weight="$4"
  local d; d="$(slotdir "$label")"
  [ -e "$d" ] && die "slot $label already exists — clean it first"
  [ "$(integrity "$MASTER")" = "ok" ] || die "MASTER integrity not ok — aborting"
  mkdir -p "$d"
  cp "$MASTER" "$d/residency_scheduler.db"
  # WAL/shm are not copied; the fresh connection recreates them. Verify copy:
  [ "$(integrity "$d/residency_scheduler.db")" = "ok" ] || die "copy integrity not ok"
  python - "$d/residency_scheduler.db" "$floor" "$target" "$weight" "$HEAVY" "$SRC" <<'EOF'
import sqlite3,sys
db=sqlite3.connect(sys.argv[1]); c=db.cursor()
def setk(k,v): c.execute("INSERT INTO schedule_config(config_key,config_value) VALUES(?,?) ON CONFLICT(config_key) DO UPDATE SET config_value=excluded.config_value",(k,str(v)))
setk('enforce_zero_volunteer_weekends',sys.argv[2])
setk('sunday_coverage_target',sys.argv[3])
setk('weight_sunday_coverage',sys.argv[4])
setk('heavy_rotation_ids',sys.argv[5]); setk('sunday_source_rotation_ids',sys.argv[6])
db.commit(); print('config written into slot copy: floor=%s target=%s weight=%s'%(sys.argv[2],sys.argv[3],sys.argv[4]))
EOF
  echo "SLOT $label ready at $d"
}

cmd_launch(){
  local label="$1" p0="$2" p1="$3" p2="$4" p3="$5"
  local d; d="$(slotdir "$label")"
  [ -d "$d" ] || die "slot $label not set up"
  ( cd "$d" && nohup java -cp "$WINCLASSES;$(cat "$CPFILE")" \
      com.residency.tools.HeadlessSolveRunner 2 "$p0" "$p1" "$p2" "$p3" > solve.log 2>&1 & echo "PID $!" )
  echo "launched $label (budgets $p0/$p1/$p2/$p3)"
}

cmd_status(){
  local label="$1" d; d="$(slotdir "$label")"
  tail -4 "$d/solve.log" 2>/dev/null || echo "(no log yet)"
  if grep -q "Solver complete\|=== RESULT ===" "$d/solve.log" 2>/dev/null; then echo "STATUS: DONE"; else echo "STATUS: running/unknown"; fi
}

cmd_merge(){
  local label="$1" vname="$2" notes="$3"
  local d; d="$(slotdir "$label")"
  [ "$(integrity "$d/residency_scheduler.db")" = "ok" ] || die "slot $label DB integrity not ok — NOT merging"
  [ "$(integrity "$MASTER")" = "ok" ] || die "MASTER integrity not ok — NOT merging"
  # Score the slot's LIVE year-2 and copy that grid into a new MASTER version.
  python - "$d/residency_scheduler.db" "$MASTER" "$vname" "$notes" <<'EOF'
import sqlite3,sys
slot,master,vname,notes=sys.argv[1],sys.argv[2],sys.argv[3],sys.argv[4]
HEAVY={'ICU','VA','Broadlawns','Younker 7 Days','Younker 7 Nights','Younker 8 Pulmonology'}
MEDIUM={'Inpatient GI','Infectious Disease'}
SRC={'Inpatient GI','Infectious Disease','Outpatient GI','Outpatient Pulmonology','Ambulatory A','Emergency Medicine','Addiction Medicine','Elective','Outpatient TIC Cardiology','Outpatient UPH Cardiology'}
s=sqlite3.connect(slot); sc=s.cursor()
rots={r[0]:r[1] for r in sc.execute('SELECT id,name FROM rotations')}
ress={r[0]:r[1] for r in sc.execute('SELECT id,name FROM residents WHERE is_auxiliary=0')}
cat=set(ress)
yb={r[0]:r[1] for r in sc.execute('SELECT id,block_number FROM blocks WHERE schedule_year=2')}
g={}; allrows=[]
for rid,rotid,bid in sc.execute('SELECT resident_id,rotation_id,block_id FROM assignments WHERE block_id IN (SELECT id FROM blocks WHERE schedule_year=2)'):
    bn=yb.get(bid)
    if bn:
        g.setdefault(rid,[None]*27)[bn]=rots.get(rotid); allrows.append((rid,rotid,bn))
vol=frag=heal=0
for w in range(1,26):
    e=sum(1 for rid in cat if g.get(rid,[None]*27)[w] in SRC and g.get(rid,[None]*27)[w] not in HEAVY and g.get(rid,[None]*27)[w+1] not in HEAVY)
    vol+=e==0;frag+=e==1;heal+=e>1
over=hh=0
def t(r):return 'HM' if r in HEAVY or r in MEDIUM else 'L'
for rid in cat:
    gg=g.get(rid,[None]*27);i=1
    while i<=26:
        if t(gg[i])=='HM':
            j=i+1
            while j<=26 and t(gg[j])=='HM':j+=1
            if (j-i)*2>6: over+=1
            i=j
        else:i+=1
    for b in range(1,26):
        if gg[b] in HEAVY and gg[b+1] in HEAVY and gg[b]!=gg[b+1]:hh+=1
print(f'{vname}: volunteer={vol} fragile={frag} healthy={heal} heavy->heavy={hh} runs>6wk={over}')
m=sqlite3.connect(master); mc=m.cursor()
nid=mc.execute('SELECT COALESCE(MAX(id),0)+1 FROM schedule_versions').fetchone()[0]
summary=f'[parallel] vol={vol} frag={frag} heal={heal} hh={hh} runs6={over}'
mc.execute("""INSERT INTO schedule_versions(id,schedule_year,name,created_at,notes,tier1_score,tier2_score,feasible,summary)
              VALUES(?,?,?,datetime('now'),?,0,0,1,?)""",(nid,2,vname,notes,summary))
mc.executemany('INSERT INTO schedule_version_assignments(version_id,resident_id,rotation_id,block_number) VALUES(?,?,?,?)',
               [(nid,rid,rotid,bn) for rid,rotid,bn in allrows])
m.commit()
print(f'MERGED into master as version {nid}; master integrity:', mc.execute('PRAGMA integrity_check').fetchone()[0][:10])
EOF
}

cmd_clean(){ rm -rf "$(slotdir "$1")"; echo "cleaned slot $1"; }

sub="${1:-}"; shift || true
case "$sub" in
  setup) cmd_setup "$@";; launch) cmd_launch "$@";; status) cmd_status "$@";;
  merge) cmd_merge "$@";; clean) cmd_clean "$@";;
  *) echo "usage: parallel_run.sh {setup|launch|status|merge|clean} ..."; exit 2;;
esac
