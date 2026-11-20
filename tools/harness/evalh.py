import re, numpy as np
from common import *
from truth import TAB
FAM = {'MAJ':'M','DOM7':'M','MAJ7':'M','AUG':'M','MIN':'m','MIN7':'m','DIM':'d','HDIM':'d','SUS4':'s','SUS2':'s'}

def parse_sym(sym):
    sym = sym.split('/')[0]
    m = re.match(r'([A-G])([#b]?)(.*)', sym)
    root = NM[m.group(1) + m.group(2)] if (m.group(1)+m.group(2)) in NM else NM[m.group(1)]
    q = m.group(3)
    if q.startswith('mmaj') or (q.startswith('m') and not q.startswith('maj')):
        fam = 'd' if 'b5' in q else 'm'
    elif q.startswith('dim'): fam = 'd'
    elif q in ('4', 'sus4', '2', 'sus2', '5'): fam = 's'
    else: fam = 'M'
    return root, fam

def tabset(text):
    return {parse_sym(s) for s in text.split()}

def match(det, T):
    if det is None or det[0] is None: return None
    (r, q), b = det
    f = FAM[q]
    if f == 's': return any(t[0] == r for t in T)
    if (r, f) in T: return True
    return (r, 's') in T

def tab_score(segs, nb, T, shift):
    T2 = {((r + shift) % 12, f) for r, f in T}
    pb = E.per_beat(segs, nb)
    ok = n = 0
    for v in pb:
        m = match(v, T2)
        if m is None: continue
        n += 1; ok += m
    return ok, n

def find_tab(title):
    for k, v in TAB.items():
        if k in title: return tabset(v)
    return None

def fixed_truth(s, S):
    """per feature-beat truth ((root,q),bass) from hand-fixed segments, else None"""
    fb = S.beats
    out = [None] * len(S.chroma)
    fixed = [c for c in s['chords'] if c['fixed']]
    for b in range(len(out)):
        mid = 0.5 * (fb[b] + fb[b + 1])
        for c in fixed:
            if c['start'] <= mid < c['end']:
                out[b] = parse_code(c['c']); break
    return out

SKIP_FIXED = ('לקחת את ידי',)   # Em7b5 fixes not confirmed

def evaluate(P=None, data=None, shifts=None, verbose=False):
    data = data or load()
    tot = dict(tab_ok=0, tab_n=0, fx_root=0, fx_fam=0, fx_full=0, fx_n=0)
    shifts = shifts if shifts is not None else {}
    rows = []
    for s, S in data:
        segs = E.run(S, P)
        nb = len(S.chroma)
        T = find_tab(s['title'])
        if T:
            if s['title'] not in shifts:
                shifts[s['title']] = max(range(12), key=lambda t: tab_score(segs, nb, T, t)[0])
            ok, n = tab_score(segs, nb, T, shifts[s['title']])
            tot['tab_ok'] += ok; tot['tab_n'] += n
            rows.append((s['title'][:20], 'tab', round(ok / n, 3)))
        if s['edited'] and not any(k in s['title'] for k in SKIP_FIXED):
            truth = fixed_truth(s, S)
            pb = E.per_beat(segs, nb)
            r_ = f_ = u_ = n_ = 0
            for t, v in zip(truth, pb):
                if t is None or v is None or v[0] is None: continue
                n_ += 1
                r_ += t[0][0] == v[0][0]
                f_ += t[0][0] == v[0][0] and FAM[t[0][1]] == FAM[v[0][1]]
                u_ += t[0] == v[0]
            tot['fx_root'] += r_; tot['fx_fam'] += f_; tot['fx_full'] += u_; tot['fx_n'] += n_
            if n_: rows.append((s['title'][:20], 'fix', n_, round(r_ / n_, 2), round(f_ / n_, 2), round(u_ / n_, 2)))
    res = dict(tab=round(tot['tab_ok'] / tot['tab_n'], 4), fx_root=round(tot['fx_root'] / tot['fx_n'], 3),
               fx_fam=round(tot['fx_fam'] / tot['fx_n'], 3), fx_full=round(tot['fx_full'] / tot['fx_n'], 3), fx_n=tot['fx_n'])
    if verbose:
        for r in rows: print(r)
    return res, shifts
