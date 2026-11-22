"""Per-beat truth from a tab4u sheet saved in the app (export format 2, song['lyrics']).

The sheet's chord lines give the chord ORDER; the beats' sound data gives the timing.
A dynamic-programming alignment walks through the sheet in order (stay / next chord /
skip a little / jump back to a section start for repeats not written out), choosing the
transposition that fits best. Beats where the sheet chord does not fit the sound at all
(instrumental parts missing from the sheet) are left as unknown.
"""
import re
import numpy as np
import engine as E
from common import NM

QMAP = [  # (regex on suffix, engine quality)
    (r'^(maj7|maj9|M7|ma7)', 'MAJ7'), (r'^(m7b5|ø)', 'HDIM'), (r'^(mmaj7|m\(maj7\))', 'MIN'),
    (r'^(m7|m9|m11|min7)', 'MIN7'), (r'^(dim|o)', 'DIM'), (r'^(aug|\+)', 'AUG'),
    (r'^(sus4|sus|4)$', 'SUS4'), (r'^(sus2|2)$', 'SUS2'), (r'^(m|min)', 'MIN'),
    (r'^(7|9|11|13)', 'DOM7'),
]
CHORD = re.compile(r'^([A-G])([#b]?)([^/\s]*)(?:/([A-G][#b]?))?$')


def parse_chord(tok):
    m = CHORD.match(tok)
    if not m:
        return None
    root = NM[m.group(1) + m.group(2)] if m.group(2) else NM[m.group(1)]
    suf = m.group(3)
    q = 'MAJ'
    for rx, qq in QMAP:
        if re.match(rx, suf):
            q = qq
            break
    else:
        if suf and not re.match(r'^(6|add9|add2|5|\(?add9\)?|6/9)$', suf):
            return None
    bass = NM[m.group(4)] if m.group(4) else root
    return (root, q, bass, tok)


def parse_sheet(text):
    """[(section_start: bool, chord)] in sheet order."""
    out, new_sec = [], True
    for line in text.replace('\xa0', ' ').split('\n'):
        toks = line.split()
        if not toks:
            new_sec = True
            continue
        toks = [t.strip('|()[]') for t in toks if t.strip('|()[]') and not re.match(r'^x?\d$|^x\d+$', t)]
        cs = [parse_chord(t) for t in toks]
        if cs and all(cs):
            for c in cs:
                out.append((new_sec, c))
                new_sec = False
    return out


def emissions(S, seq, shift, bass_w=0.15):
    T = np.array([E.template(((c[0] + shift) % 12, c[1])) for _, c in seq])
    e = S.chroma @ T.T
    bass = np.array([(c[2] + shift) % 12 for _, c in seq])
    e = e + bass_w * S.bassN[:, bass]
    return e


def align(S, seq, shift, adv=0.05, skip=1.5, jump=1.2, mind=2):
    """Each sheet chord lasts >= mind beats; walk in order with stay/next, or pay to skip
    ahead or jump back to a section start (repeats not written out)."""
    e0 = emissions(S, seq, shift)
    nb, M = e0.shape
    starts = np.array([s for s, _ in seq])
    # state (j, d): d = beats already spent in chord j, capped at mind-1
    NS = M * mind
    e = np.repeat(e0, mind, axis=1)
    V = np.full(NS, -1e9)
    V[0::mind] = e[0, 0::mind]          # may start at any chord
    back = np.zeros((nb, NS), int)
    J = np.arange(NS) // mind
    D = np.arange(NS) % mind
    for b in range(1, nb):
        newV = np.full(NS, -1e9)
        src = np.zeros(NS, int)
        # continue within chord: (j,d)->(j,d+1); last d stays
        for d in range(1, mind):
            idx = np.arange(M) * mind + d
            newV[idx] = V[idx - 1]; src[idx] = idx - 1
        last = np.arange(M) * mind + mind - 1
        stay = V[last]
        upd = stay > newV[last]
        newV[last] = np.where(upd, stay, newV[last]); src[last] = np.where(upd, last, src[last])
        # entering chord j (d=0): from finished j-1, from finished k<j-1 (skip), or jump to a section start
        fin = V[last]                     # finished chords
        ent = np.arange(M) * mind
        nxt = np.concatenate([[-1e9], fin[:-1] - adv])
        nxt_src = np.concatenate([[0], last[:-1]])
        pm = np.full(M, -1e9); pmi = np.zeros(M, int)
        rv, ri = -1e9, 0
        for j in range(M):
            if j >= 2 and fin[j - 2] > rv:
                rv, ri = fin[j - 2], last[j - 2]
            pm[j] = rv - skip; pmi[j] = ri
        bi = int(np.argmax(fin))
        jp = np.where(starts, fin[bi] - jump, -1e9)
        cand = np.stack([nxt, pm, jp])
        ch = np.argmax(cand, 0)
        val = cand[ch, np.arange(M)]
        newV[ent] = val
        src[ent] = np.where(ch == 0, nxt_src, np.where(ch == 1, pmi, last[bi]))
        back[b] = src
        V = newV + e[b]
    st = int(np.argmax(V))
    total = float(V[st])
    path = [0] * nb
    for b in range(nb - 1, -1, -1):
        path[b] = st // mind
        st = back[b, st]
    return path, total, e0


def truth_for(S, text, min_fit=0.55):
    seq = parse_sheet(text)
    if len(seq) < 4:
        return None
    best = None
    for sh in range(12):
        p, tot, e = align(S, seq, sh)
        if best is None or tot > best[1]:
            best = (p, tot, e, sh)
    path, tot, e, sh = best
    out = []
    for b, j in enumerate(path):
        r, q, bass, tok = seq[j][1]
        fit = e[b, j]
        out.append((((r + sh) % 12, q), (bass + sh) % 12, tok, j) if fit >= min_fit else None)
    return dict(truth=out, shift=sh, seq=seq, score=tot / len(path))
