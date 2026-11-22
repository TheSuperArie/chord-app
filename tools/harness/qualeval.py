"""Quality metric from tab4u sheets: on beats whose detected root appears in the sheet with
one chord family only, is the detected family / exact quality the sheet's?"""
import sys, json
import numpy as np, engine as E
from common import parse_key
from tabalign import parse_sheet
FAM = {'MAJ':'M','DOM7':'M','MAJ7':'M','AUG':'M','MIN':'m','MIN7':'m','DIM':'d','HDIM':'d','SUS4':'s','SUS2':'s'}

def load_q(path):
    d = json.load(open(path)); out = []
    for s in d['songs']:
        if not s.get('lyrics') or 'features' not in s: continue
        S = E.Song(s['features'], parse_key(s['key']), s['bpb'], s['downbeatPhase'])
        seq = [c for _, c in parse_sheet(s['lyrics'])]
        if len(seq) < 4: continue
        # transposition: the one whose roots cover most of the detected roots
        # transposition: the one whose sheet chords best explain the sound
        def fit(t):
            T = np.array([E.template(((c[0] + t) % 12, c[1])) for c in set((c[0], c[1]) for c in seq)])
            return float((S.chroma @ T.T).max(1).sum())
        sh = max(range(12), key=fit)
        fams = {}
        quals = {}
        for r, q, b, tok in seq:
            R = (r + sh) % 12
            fams.setdefault(R, set()).add(FAM[q]); quals.setdefault(R, {}).setdefault(q, 0); quals[R][q] += 1
        out.append((s, S, fams, quals))
    return out

def qscore(data, P=None, verbose=False, errs=None):
    T = dict(n=0, fam=0, q=0, cov=0, all=0)
    for s, S, fams, quals in data:
        pb = E.per_beat(E.run(S, P), len(S.chroma))
        t = dict(n=0, fam=0, q=0, cov=0, all=0)
        for b, v in enumerate(pb):
            if not v or not v[0]: continue
            (r, q), _ = v
            t['all'] += 1
            if r in fams: t['cov'] += 1
            if r not in fams or len(fams[r]) != 1: continue
            f = next(iter(fams[r]))
            if f == 's': continue
            t['n'] += 1
            t['fam'] += FAM[q] == f
            t['q'] += q in quals[r]
            if errs is not None and FAM[q] != f: errs.append((s['title'][:10], b, E.NAMES[r], q, f))
        for k in T: T[k] += t[k]
        if verbose:
            n = max(t['n'], 1)
            print(f"{s['title'][:22]:22} n={t['n']:4} rootInSheet={t['cov']/max(t['all'],1):.2f} fam={t['fam']/n:.3f} exactQ={t['q']/n:.3f}")
    n = max(T['n'], 1)
    return dict(fam=round(T['fam']/n, 4), q=round(T['q']/n, 4), root_in_sheet=round(T['cov']/max(T['all'],1), 4), n=T['n'])

if __name__ == '__main__':
    data = load_q(sys.argv[1])
    print(qscore(data, verbose=True))
