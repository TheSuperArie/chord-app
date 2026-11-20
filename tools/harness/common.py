import json, sys
sys.path.insert(0, '/home/claude/harness')
import engine as E
NM = {n: i for i, n in enumerate(E.NAMES)}
NM.update({'C#': 1, 'Db': 1, 'D#': 3, 'Eb': 3, 'F#': 6, 'Gb': 6, 'G#': 8, 'Ab': 8, 'A#': 10, 'Bb': 10})

def parse_key(k):
    minor = k.endswith('m')
    return (NM[k[:-1] if minor else k], minor)

def parse_code(x):
    if x in ('N', None) or x.startswith('-1'):
        return None
    r, q, b = x.split(':')
    return ((int(r), q), int(b))

def load(path='/home/claude/in3/d.json'):
    d = json.load(open(path))
    out = []
    for s in d['songs']:
        f = s['features']
        S = E.Song(f, parse_key(s['key']), s['bpb'], s['downbeatPhase'])
        out.append((s, S))
    return out

def song_beats_codes(s, field):
    nb = max(c['eb'] for c in s['chords'])
    out = [None] * nb
    for c in s['chords']:
        v = parse_code(c[field])
        for k in range(c['sb'], c['eb']):
            out[k] = v
    return out
