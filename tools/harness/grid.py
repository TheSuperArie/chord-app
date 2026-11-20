import time, itertools, sys, json
from evalh import *
d = load()
base, sh = evaluate(data=d)
print('base', base)
exps = {
 'strong_third': [0.2, 0.25, 0.35, 0.45],
 'third_ratio': [1.15, 1.5],
 'song_mode_ratio': [1.1, 1.5],
 'min_bass_third': [0.3, 0.6],
 'seventh_evidence': [0.3, 0.5, 0.7],
 'dia_bonus': [0.0, 0.02, 0.05],
 'bass_w': [0.06, 0.18, 0.25],
 'kappa': [16, 32],
 'p_other': [0.03, 0.1],
 'refine_cplx': [0.4, 1.0, 1.5],
}
for k, vals in exps.items():
    for v in vals:
        t = time.time()
        r, _ = evaluate({k: v}, data=d, shifts=dict(sh))
        print(k, v, r, round(time.time() - t, 1))
        sys.stdout.flush()
