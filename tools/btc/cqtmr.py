"""Multi-rate CQT as the app will compute it: input at 11025 Hz, octave o computed at 11025/2^(5-o).
Output matches the ideal 22050-Hz CQT used by BTC (frames centred at t*1024 samples @11025)."""
import numpy as np
SR0 = 11025; HOP0 = 1024; NB = 144; BPO = 24; FMIN = 32.70319566257483
Q = 1.0 / (2 ** (1 / BPO) - 1)

def halfband(ntap=63):
    n = np.arange(ntap) - (ntap - 1) / 2
    h = 0.5 * np.sinc(0.5 * n) * np.blackman(ntap)  # cutoff = quarter of sample rate
    return (h / h.sum()).astype(np.float32)
H = halfband()

def decimate(y):
    z = np.convolve(y, H, mode='same')
    return z[::2]

def kernels():
    """Per octave: (rate, hop, [(L22050, n, start, complex kernel)])"""
    out = []
    for o in range(6):
        down = 2 ** (5 - o); sr = SR0 / down; hop = HOP0 // down
        ks = []
        for j in range(BPO):
            k = o * BPO + j
            f = FMIN * 2 ** (k / BPO)
            l = Q * sr / f; n = int(np.floor(l)); start = -n // 2
            t = np.arange(n) + start
            win = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / n)
            ker = win * np.exp(2j * np.pi * f * t / sr); ker /= np.abs(ker).sum()
            ks.append((Q * 22050 / f, n, start, ker))
        out.append((sr, hop, ks))
    return out
KER = kernels()

def cqt_chunk(y):
    nfr = 1 + len(y) // HOP0
    C = np.zeros((NB, nfr), np.float32)
    sig = y.astype(np.float64)
    sigs = [None] * 6
    for o in range(5, -1, -1):
        sigs[o] = sig
        if o: sig = decimate(sig)
    for o, (sr, hop, ks) in enumerate(KER):
        s = sigs[o]
        maxn = max(k[1] for k in ks); pad = maxn // 2 + 2
        sp = np.pad(s, pad, mode='reflect')
        for j, (L22, n, start, ker) in enumerate(ks):
            idx = (np.arange(nfr) * hop)[:, None] + (np.arange(n) + start)[None, :] + pad
            C[o * BPO + j] = np.abs(sp[idx] @ ker) * np.sqrt(L22)
    return C

def features(y11):
    step = 110250; parts = []; s = 0
    while len(y11) > s + step:
        parts.append(cqt_chunk(y11[s:s + step])); s += step
    parts.append(cqt_chunk(y11[s:]))
    return np.log(np.concatenate(parts, 1) + 1e-6).T
