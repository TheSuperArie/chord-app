import json, sys
import numpy as np
import engine as E
from common import parse_key
from tabalign import truth_for
FAM = {'MAJ':'M','DOM7':'M','MAJ7':'M','AUG':'M','MIN':'m','MIN7':'m','DIM':'d','HDIM':'d','SUS4':'s','SUS2':'s'}

def load_tab(path):
    d = json.load(open(path))
    out = []
    for s in d['songs']:
        if not s.get('lyrics') or 'features' not in s: continue
        S = E.Song(s['features'], parse_key(s['key']), s['bpb'], s['downbeatPhase'])
        tr = truth_for(S, s['lyrics'])
        if tr: out.append((s, S, tr))
    return out

def score(data, P=None, verbose=False, conf=None):
    T = dict(n=0, root=0, fam=0, full=0, bass=0)
    for s, S, tr in data:
        segs = E.run(S, P); nb = len(S.chroma)
        pb = E.per_beat(segs, nb)
        t = dict(n=0, root=0, fam=0, full=0, bass=0)
        for b, (tv, v) in enumerate(zip(tr['truth'], pb)):
            if tv is None or v is None or v[0] is None: continue
            (r, q), tb, tok, j = tv
            (vr, vq), vb = v
            vb = vr if vb < 0 else vb
            t['n'] += 1
            t['root'] += vr == r
            t['fam'] += vr == r and FAM[vq] == FAM[q]
            t['full'] += (vr, vq) == (r, q)
            t['bass'] += vb == tb
            if conf is not None and vr == r and FAM[vq] != FAM[q]:
                conf.append((s['title'][:12], b, tok, E.name((vr, vq), vb)))
        for k in T: T[k] += t[k]
        if verbose:
            n = max(t['n'], 1)
            print(f"{s['title'][:22]:22} shift={tr['shift']:2} fit={tr['score']:.2f} n={t['n']:4} known={t['n']/nb:.0%} root={t['root']/n:.3f} fam={t['fam']/n:.3f} full={t['full']/n:.3f} bass={t['bass']/n:.3f}")
    n = T['n']
    return {k: round(T[k] / n, 4) for k in ('root', 'fam', 'full', 'bass')} | {'n': n}

if __name__ == '__main__':
    data = load_tab(sys.argv[1])
    print(score(data, verbose=True))
