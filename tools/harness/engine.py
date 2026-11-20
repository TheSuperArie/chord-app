"""Python port of the chord stage of Analyzer.kt (features -> chord segments).

Mirrors engine 7: beatScores, viterbi, absorbPassingBass, refineQualities, buildSegments
(slash basses + seventh-on-bass naming). Parameters live in P so they can be tuned.
"""
import math
import numpy as np

QUAL = {  # name: intervals
    'MAJ': (0, 4, 7), 'MIN': (0, 3, 7), 'DOM7': (0, 4, 7, 10), 'MAJ7': (0, 4, 7, 11),
    'MIN7': (0, 3, 7, 10), 'SUS4': (0, 5, 7), 'SUS2': (0, 2, 7), 'DIM': (0, 3, 6),
    'AUG': (0, 4, 8), 'HDIM': (0, 3, 6, 10),
}
QORDER = list(QUAL)
TRIAD = {'DOM7': 'MAJ', 'MAJ7': 'MAJ', 'MIN7': 'MIN', 'HDIM': 'DIM'}
SUFFIX = {'MAJ': '', 'MIN': 'm', 'DOM7': '7', 'MAJ7': 'maj7', 'MIN7': 'm7', 'SUS4': 'sus4',
          'SUS2': 'sus2', 'DIM': 'dim', 'AUG': 'aug', 'HDIM': 'm7b5'}
NAMES = ['C', 'C#', 'D', 'Eb', 'E', 'F', 'F#', 'G', 'Ab', 'A', 'Bb', 'B']
MAJOR_FAMILY = {'MAJ', 'DOM7', 'MAJ7', 'AUG'}

VOCAB = [(r, q) for q in QORDER for r in range(12)]
NONE = len(VOCAB)


def name(c, bass=-1):
    if c is None:
        return 'N'
    r, q = c
    s = NAMES[r] + SUFFIX[q]
    return s + ('/' + NAMES[bass] if bass >= 0 and bass != r else '')


def pcs(c):
    r, q = c
    return [(r + i) % 12 for i in QUAL[q]]


def template(c):
    t = np.zeros(12)
    partials = (0, 12, 19, 24, 28)
    weights = (1.0, 0.5, 0.33, 0.25, 0.2)
    r, q = c
    for idx, iv in enumerate(QUAL[q]):
        w = 1.1 if idx == 0 else 1.0
        for p, pw in zip(partials, weights):
            t[(r + iv + p) % 12] += w * pw
    return t / np.linalg.norm(t)


TEMPL = np.array([template(c) for c in VOCAB])


def diatonic(tonic, minor):
    def c(i, q):
        return ((tonic + i) % 12, q)
    if not minor:
        return {c(0, 'MAJ'), c(0, 'MAJ7'), c(2, 'MIN'), c(2, 'MIN7'), c(4, 'MIN'), c(4, 'MIN7'),
                c(5, 'MAJ'), c(5, 'MAJ7'), c(7, 'MAJ'), c(7, 'DOM7'), c(9, 'MIN'), c(9, 'MIN7'),
                c(11, 'DIM'), c(11, 'HDIM'), c(2, 'SUS2'), c(7, 'SUS4'), c(0, 'SUS4'),
                c(4, 'MAJ'), c(4, 'DOM7')}
    return {c(0, 'MIN'), c(0, 'MIN7'), c(2, 'DIM'), c(2, 'HDIM'), c(3, 'MAJ'), c(3, 'MAJ7'),
            c(5, 'MIN'), c(5, 'MIN7'), c(7, 'MIN'), c(7, 'MAJ'), c(7, 'DOM7'), c(8, 'MAJ'),
            c(8, 'MAJ7'), c(10, 'MAJ'), c(10, 'DOM7'), c(7, 'SUS4')}


DEFAULT = dict(
    complexity={'MAJ': 0.0, 'MIN': 0.0, 'DOM7': -0.025, 'MIN7': -0.03, 'MAJ7': -0.035,
                'SUS4': -0.07, 'SUS2': -0.075, 'DIM': -0.06, 'AUG': -0.08, 'HDIM': -0.06},
    dia_bonus=0.035, bass_w=0.12, kappa=24.0,
    p_down=0.35, p_half=0.15, p_other=0.06,
    refine=True, strong_third=0.3, third_ratio=1.3, song_mode_ratio=1.25,
    sus_ratio=1.5, sus_root=0.4, refine_cplx=0.7,
    absorb=True,
    # experimental knobs (off = engine 7)
    min_bass_third=0.0,       # in refine: weight of bass chroma on 3rd evidence
    seventh_evidence=None,
    weak_ratio=0.0, weak_floor=0.05,
    inversion_margin=0.02, inversion_margin_plain=0.08,  # engine 8    # in refine: require 7th energy > x * fifth to pick a 7th chord
)


def process_chroma(c):
    c = np.asarray(c, float)
    floor = np.sort(c)[3]
    out = np.maximum(0, c - floor)
    n = np.linalg.norm(out)
    return out / n if n > 1e-12 else out


def norm(v):
    v = np.asarray(v, float)
    n = np.linalg.norm(v)
    return v / n if n > 1e-12 else v


class Song:
    def __init__(self, feat, key, bpb, phase):
        self.beats = feat['beats']
        self.raw = np.array(feat['chroma'], float)
        self.bass_raw = np.array(feat['bass'], float)
        self.energy = np.array(feat['energy'], float)
        self.chroma = np.array([process_chroma(c) for c in self.raw])
        self.bassN = np.array([norm(b) for b in self.bass_raw])
        self.med = float(np.sort(self.energy)[len(self.energy) // 2]) if len(self.energy) else 1.0
        self.key = key  # (tonic, minor)
        self.bpb, self.phase = bpb, phase


def beat_scores(S, P, dia_mask):
    """(nb, ns) emission scores."""
    cplx = np.array([P['complexity'][q] for (_, q) in VOCAB])
    sc = S.chroma @ TEMPL.T + cplx + P['dia_bonus'] * dia_mask
    roots = np.array([r for (r, _) in VOCAB])
    sc = sc + P['bass_w'] * S.bassN[:, roots]
    mean = S.chroma.mean(1)
    geo = np.exp(np.log(S.chroma + 1e-3).mean(1))
    flat = geo / (mean + 1e-9)
    quiet = S.energy < 0.08 * S.med
    none = np.where(quiet, 2.0, 0.45 + 0.5 * flat)
    return np.concatenate([sc, none[:, None]], 1)


def dia_mask_for(key):
    d = diatonic(*key)
    return np.array([1.0 if c in d else 0.0 for c in VOCAB])


def viterbi(S, P, key):
    nb = len(S.chroma)
    ns = NONE + 1
    em = beat_scores(S, P, dia_mask_for(key))
    k = P['kappa']
    prev = k * em[0]
    back = np.zeros((nb, ns), int)
    for b in range(1, nb):
        down = b >= S.phase and (b - S.phase) % S.bpb == 0
        half = S.bpb == 4 and b >= S.phase and (b - S.phase) % S.bpb == 2
        pc = P['p_down'] if down else P['p_half'] if half else P['p_other']
        stay, move = math.log(1 - pc), math.log(pc / (ns - 1))
        bp = int(np.argmax(prev))
        via_stay = prev + stay
        via_move = prev[bp] + move
        use_move = (via_move > via_stay) & (np.arange(ns) != bp)
        cur = np.where(use_move, via_move, via_stay) + k * em[b]
        back[b] = np.where(use_move, bp, np.arange(ns))
        prev = cur
    path = np.zeros(nb, int)
    s = int(np.argmax(prev))
    for b in range(nb - 1, -1, -1):
        path[b] = s
        s = back[b, s]
    return np.where(path == NONE, -1, path)


def absorb_passing_bass(path, S):
    nb = len(path)

    def isdown(b):
        return b >= S.phase and (b - S.phase) % S.bpb == 0
    b = 0
    while b < nb:
        e = b + 1
        while e < nb and path[e] == path[b]:
            e += 1
        cur = path[b]
        prev = path[b - 1] if b > 0 else -1
        if cur >= 0 and prev >= 0 and VOCAB[cur][0] != VOCAB[prev][0] and not isdown(b) \
                and e - b < S.bpb and (e >= nb or isdown(e)):
            sc = sum(S.chroma[k] @ TEMPL[cur] for k in range(b, e))
            sp = sum(S.chroma[k] @ TEMPL[prev] for k in range(b, e))
            if sp >= sc - 0.05 * (e - b):
                path[b:e] = prev
        b = e


def refine_qualities(path, S, P, key):
    dia = diatonic(*key)
    nb = len(path)
    sumM = np.zeros(12)
    summ = np.zeros(12)
    for b in range(nb):
        if path[b] < 0:
            continue
        r = VOCAB[path[b]][0]
        sumM[r] += S.chroma[b][(r + 4) % 12]
        summ[r] += S.chroma[b][(r + 3) % 12]
    R = P['song_mode_ratio']
    song_mode = [1 if sumM[r] > R * summ[r] else -1 if summ[r] > R * sumM[r] else 0 for r in range(12)]
    b = 0
    while b < nb:
        e = b + 1
        while e < nb and path[e] == path[b]:
            e += 1
        idx = path[b]
        if idx >= 0:
            avg = norm(S.chroma[b:e].sum(0))
            root = VOCAB[idx][0]
            eR = avg[root]
            e3M, e3m = avg[(root + 4) % 12], avg[(root + 3) % 12]
            if P['min_bass_third']:
                bb = norm(S.bass_raw[b:e].sum(0))
                e3M += P['min_bass_third'] * bb[(root + 4) % 12]
                e3m += P['min_bass_third'] * bb[(root + 3) % 12]
            third = max(e3M, e3m)
            strong = third > P['strong_third'] * eR
            TR = P['third_ratio']
            if strong and e3m > TR * e3M:
                mode = -1
            elif strong and e3M > TR * e3m:
                mode = 1
            elif P['weak_ratio'] and third > P['weak_floor'] * eR and e3M > P['weak_ratio'] * e3m:
                mode = 1
            elif P['weak_ratio'] and third > P['weak_floor'] * eR and e3m > P['weak_ratio'] * e3M:
                mode = -1
            elif song_mode[root] != 0:
                mode = song_mode[root]
            elif (root, 'MIN') in dia and (root, 'MAJ') not in dia:
                mode = -1
            else:
                mode = 1
            e4, e2 = avg[(root + 5) % 12], avg[(root + 2) % 12]
            sus4ok = e4 > P['sus_ratio'] * third and e4 > P['sus_root'] * eR
            sus2ok = e2 > P['sus_ratio'] * third and e2 > P['sus_root'] * eR
            best, bestS = -1, -1e9
            for i, c in enumerate(VOCAB):
                if c[0] != root:
                    continue
                q = c[1]
                if q == 'SUS4':
                    ok = sus4ok
                elif q == 'SUS2':
                    ok = sus2ok
                elif q in MAJOR_FAMILY:
                    ok = mode > 0
                else:
                    ok = mode < 0
                if not ok:
                    continue
                if P['seventh_evidence'] is not None and len(QUAL[q]) == 4:
                    sev = avg[(root + QUAL[q][3]) % 12]
                    fifth = avg[(root + QUAL[q][2]) % 12]
                    if sev < P['seventh_evidence'] * max(fifth, 1e-9):
                        continue
                sc = avg @ TEMPL[i] + P['refine_cplx'] * P['complexity'][q]
                if c in dia:
                    sc += 0.02
                if i == idx:
                    sc += 0.01
                if sc > bestS:
                    bestS, best = sc, i
            if best >= 0:
                path[b:e] = best
        b = e


SEVENTHS = ['MIN7', 'HDIM', 'MAJ7', 'DOM7']


def seventh_on_bass(c, bass):
    r, q = c
    if bass < 0 or bass == r or len(QUAL[q]) == 4 or bass in pcs(c):
        return None
    notes = set(pcs(c)) | {bass}
    for q2 in SEVENTHS:
        if set(pcs((bass, q2))) == notes:
            return (bass, q2)
    return None


def bass_runs(beat_bass, b, e, chord, S):
    root = chord[0]
    seq = [root if beat_bass[k] < 0 else beat_bass[k] for k in range(b, e)]
    if seq and seq[0] == root:
        fifth = (root + 7) % 12
        seq = [root if v == fifth else v for v in seq]
    runs = []
    i = 0
    while i < len(seq):
        j = i + 1
        while j < len(seq) and seq[j] == seq[i]:
            j += 1
        runs.append([b + i, b + j, seq[i]])
        i = j
    changed = True
    while changed and len(runs) > 1:
        changed = False
        for k, r in enumerate(runs):
            if r[1] - r[0] < 2:
                if k > 0:
                    runs[k - 1][1] = r[1]
                else:
                    runs[k + 1][0] = r[0]
                runs.pop(k)
                changed = True
                break
    joined = []
    for r in runs:
        mid = not (r[0] >= S.phase and (r[0] - S.phase) % S.bpb == 0)
        if joined and (mid or joined[-1][2] == r[2]):
            joined[-1][1] = r[1]
        else:
            joined.append(r)
    return joined


def build_segments(path, S):
    """Returns list of (sb, eb, chord|None, bass)."""
    nb = len(path)
    beat_bass = []
    for b in range(nb):
        v = S.bass_raw[b]
        i1 = int(np.argmax(v))
        second = max(v[p] for p in range(12) if p != i1)
        beat_bass.append(i1 if v[i1] > 1.15 * second and v[i1] > 0.05 else -1)
    out = []
    b = 0
    while b < nb:
        e = b + 1
        while e < nb and path[e] == path[b]:
            e += 1
        idx = path[b]
        if idx < 0:
            out.append([b, e, None, -1])
            b = e
            continue
        base = VOCAB[idx]
        for rs, re_, bpc in bass_runs(beat_bass, b, e, base, S):
            chord, bass = base, -1
            if bpc >= 0 and bpc != base[0]:
                avg = S.bass_raw[rs:re_].sum(0)
                if avg[bpc] > 1.3 * avg[base[0]]:
                    bass = bpc
                    q = base[1]
                    if len(QUAL[q]) == 4 and bpc == (base[0] + QUAL[q][3]) % 12:
                        chord = (base[0], TRIAD[q])
                    s7 = seventh_on_bass(chord, bass)
                    if s7:
                        chord, bass = s7, -1
            out.append([rs, re_, chord, bass])
        b = e
    merged = []
    for s in out:
        if merged and merged[-1][2] == s[2] and merged[-1][3] == s[3]:
            merged[-1][1] = s[1]
        else:
            merged.append(s)
    return merged


def inversions(path, S, P, key):
    """A run whose chord was chosen for its bass (root = bass) but whose upper notes fit a
    chord containing that bass clearly better becomes that chord over the bass (Ab -> Fm/Ab)."""
    m = P['inversion_margin']
    cplx = np.array([P['complexity'][q] for (_, q) in VOCAB])
    nb = len(path)
    b = 0
    while b < nb:
        e = b + 1
        while e < nb and path[e] == path[b]:
            e += 1
        idx = path[b]
        if idx >= 0:
            r = VOCAB[idx][0]
            bb = S.bass_raw[b:e].sum(0)
            if np.argmax(bb) == r:
                avg = norm(S.chroma[b:e].sum(0))
                sc = TEMPL @ avg + cplx
                best = -1
                for i, c in enumerate(VOCAB):
                    if c[0] == r or r not in pcs(c) or c[1] in ('SUS4', 'SUS2', 'AUG', 'DIM'):
                        continue
                    if best < 0 or sc[i] > sc[best]:
                        best = i
                mm = P['inversion_margin_plain'] if VOCAB[idx][1] in ('MAJ', 'MIN') else m
                if best >= 0 and sc[best] > sc[idx] + mm:
                    path[b:e] = best
        b = e


def run(S, P=None, key=None):
    P = {**DEFAULT, **(P or {})}
    key = key or S.key
    path = viterbi(S, P, key)
    if P['absorb']:
        absorb_passing_bass(path, S)
    if P['refine']:
        refine_qualities(path, S, P, key)
    if P['inversion_margin'] is not None:
        inversions(path, S, P, key)
    return build_segments(path, S)


def per_beat(segs, nb):
    out = [None] * nb
    for sb, eb, c, bass in segs:
        for k in range(sb, min(eb, nb)):
            out[k] = (c, bass)
    return out
