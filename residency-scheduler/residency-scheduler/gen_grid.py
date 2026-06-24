#!/usr/bin/env python3
"""Generate schedule-grid tables (Markdown + tier-shaded HTML) from the DB.

Reads a saved version (schedule_version_assignments) or LIVE (assignments) and emits
the grid in the exact format used by SCHEDULE_ITERATION_REPORT.{md,html}.

Usage:
  python gen_grid.py --version 13 --title "App cfgA ..." [--html out.html] [--md out.md]
  python gen_grid.py --live 2     --title "..."
"""
import sqlite3, argparse, sys

DB='residency_scheduler.db'
HEAVY={'ICU','VA','Broadlawns','Younker 7 Days','Younker 7 Nights','Younker 8 Pulmonology'}
MEDIUM={'Inpatient GI','Infectious Disease'}
# Display abbreviations (match the report legend)
ABBR={
 'ICU':'ICU','VA':'VA','Broadlawns':'BMC','Younker 7 Days':'Y7D','Younker 7 Nights':'Y7N',
 'Younker 8 Pulmonology':'Y8','Inpatient GI':'GI','Infectious Disease':'ID',
 'Ambulatory A':'AMB A','Outpatient TIC Cardiology':'OPC TIC','Outpatient UPH Cardiology':'OPC UPH',
 'Outpatient GI':'AMB GI','Outpatient Pulmonology':'AMB P','Emergency Medicine':'ER',
 'Addiction Medicine':'ADDMED','Elective':'Elec',
}
def tier_class(name):
    if name in HEAVY: return 't-h'
    if name in MEDIUM: return 't-m'
    return 't-l'

def load_grid(con, version=None, live_year=None):
    c=con.cursor()
    rots={r[0]:r[1] for r in c.execute('SELECT id,name FROM rotations')}
    ress={r[0]:r[1] for r in c.execute('SELECT id,name FROM residents WHERE is_auxiliary=0 ORDER BY name')}
    grid={rid:[None]*27 for rid in ress}
    if version is not None:
        rows=c.execute('SELECT resident_id,rotation_id,block_number FROM schedule_version_assignments WHERE version_id=?',(version,))
        for rid,rotid,bn in rows:
            if rid in grid and 1<=bn<=26: grid[rid][bn]=rots.get(rotid)
    else:
        yb={r[0]:r[1] for r in c.execute('SELECT id,block_number FROM blocks WHERE schedule_year=?',(live_year,))}
        rows=c.execute('SELECT resident_id,rotation_id,block_id FROM assignments WHERE block_id IN (SELECT id FROM blocks WHERE schedule_year=?)',(live_year,))
        for rid,rotid,bid in rows:
            bn=yb.get(bid)
            if rid in grid and bn: grid[rid][bn]=rots.get(rotid)
    return ress, grid

COLS=[f'{b}{ab}' for b in range(1,14) for ab in ('a','b')]  # 1a..13b

def to_md(ress, grid, title):
    out=[f'### {title}\n']
    out.append('| Res | '+' | '.join(COLS)+' |')
    out.append('|---|'+'---|'*26)
    for rid,name in ress.items():
        letter=name.replace('Res ','')
        cells=[]
        for slot in range(1,27):
            r=grid[rid][slot]
            cells.append(ABBR.get(r,r) if r else '—')
        out.append(f'| {letter} | '+' | '.join(cells)+' |')
    return '\n'.join(out)+'\n'

def to_html(ress, grid, title):
    out=[f'<div class="gridtitle">{title}</div>']
    out.append('<table class="schedgrid"><thead><tr><th class="rh">Res</th>')
    out+=[f'<th>{col}</th>' for col in COLS]
    out.append('</tr></thead><tbody>')
    for rid,name in ress.items():
        letter=name.replace('Res ','')
        out.append(f'<tr><th class="rh">{letter}</th>')
        for slot in range(1,27):
            r=grid[rid][slot]
            if r: out.append(f'<td class="{tier_class(r)}">{ABBR.get(r,r)}</td>')
            else: out.append('<td>—</td>')
        out.append('</tr>')
    out.append('</tbody></table>')
    return '\n'.join(out)+'\n'

if __name__=='__main__':
    ap=argparse.ArgumentParser()
    ap.add_argument('--version',type=int); ap.add_argument('--live',type=int)
    ap.add_argument('--title',required=True)
    ap.add_argument('--md'); ap.add_argument('--html')
    a=ap.parse_args()
    con=sqlite3.connect(DB)
    ress,grid=load_grid(con, version=a.version, live_year=a.live)
    md=to_md(ress,grid,a.title); html=to_html(ress,grid,a.title)
    if a.md:
        open(a.md,'w',encoding='utf-8').write(md); print('wrote',a.md)
    if a.html:
        open(a.html,'w',encoding='utf-8').write(html); print('wrote',a.html)
    if not a.md and not a.html:
        print(md); print('\n----HTML----\n'); print(html)
