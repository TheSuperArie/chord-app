"""BTC (Park et al. 2019, MIT) forward pass + CQT front end in numpy - reference for the Kotlin port."""
import numpy as np, math
from loadpt import load
SR, HOP, NB, BPO, T = 22050, 2048, 144, 24, 108
FMIN = 32.70319566257483  # C1
QUALS = ['min', 'maj', 'dim', 'aug', 'min6', 'maj6', 'min7', 'minmaj7', 'maj7', '7', 'dim7', 'hdim7', 'sus2', 'sus4']
ROOTS = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']

def label(i):
    if i == 169: return 'N'
    if i == 168: return 'X'
    return ROOTS[i // 14] + ('' if i % 14 == 1 else ':' + QUALS[i % 14])

class BTC:
    def __init__(self, path):
        ck = load(path)
        self.W = {k.replace('self_attn_layers.self_attn_layers.', 'L').replace('self_attn_layers.', ''): v.astype(np.float32)
                  for k, v in ck['model'].items()}
        self.mean, self.std = float(ck['mean']), float(ck['std'])
        pos = np.arange(T)[:, None]; nts = 64
        inv = np.exp(np.arange(nts) * -(math.log(1e4) / (nts - 1)))
        st = pos * inv[None]
        self.timing = np.concatenate([np.sin(st), np.cos(st)], 1).astype(np.float32)
        self.mask = np.triu(np.full((T, T), -np.inf, np.float32), 1)

    @staticmethod
    def ln(x, g, b):
        m = x.mean(-1, keepdims=True); s = x.std(-1, ddof=1, keepdims=True)
        return g * (x - m) / (s + 1e-6) + b

    @staticmethod
    def conv(x, w, b):  # x [T,C], w [out,in,3], 'both' padding (1,1)
        xp = np.pad(x, ((1, 1), (0, 0)))
        return xp[:-2] @ w[:, :, 0].T + xp[1:-1] @ w[:, :, 1].T + xp[2:] @ w[:, :, 2].T + b

    def block(self, x, p, mask):
        W = self.W
        xn = self.ln(x, W[p + 'layer_norm_mha.gamma'], W[p + 'layer_norm_mha.beta'])
        a = p + 'multi_head_attention.'
        q = xn @ W[a + 'query_linear.weight'].T; k = xn @ W[a + 'key_linear.weight'].T; v = xn @ W[a + 'value_linear.weight'].T
        n = x.shape[0]
        q = q.reshape(n, 4, 32).transpose(1, 0, 2) * 32 ** -0.5
        k = k.reshape(n, 4, 32).transpose(1, 0, 2); v = v.reshape(n, 4, 32).transpose(1, 0, 2)
        lg = q @ k.transpose(0, 2, 1) + mask[:n, :n]
        lg = lg - lg.max(-1, keepdims=True); w = np.exp(lg); w /= w.sum(-1, keepdims=True)
        c = (w @ v).transpose(1, 0, 2).reshape(n, 128)
        x = x + c @ W[a + 'output_linear.weight'].T
        xn = self.ln(x, W[p + 'layer_norm_ffn.gamma'], W[p + 'layer_norm_ffn.beta'])
        f = p + 'positionwise_convolution.layers.'
        h = np.maximum(self.conv(xn, W[f + '0.conv.weight'], W[f + '0.conv.bias']), 0)
        h = np.maximum(self.conv(h, W[f + '1.conv.weight'], W[f + '1.conv.bias']), 0)
        return x + h

    def window_logits(self, feat):  # feat [T,144] normalized
        W = self.W
        x = feat @ W['embedding_proj.weight'].T + self.timing[:feat.shape[0]]
        for l in range(8):
            p = f'L{l}.'
            fwd = self.block(x, p + 'attn_block.', self.mask)
            bwd = self.block(x, p + 'backward_attn_block.', self.mask.T)
            x = np.concatenate([fwd, bwd], 1) @ W[p + 'linear.weight'].T + W[p + 'linear.bias']
        x = self.ln(x, W['layer_norm.gamma'], W['layer_norm.beta'])
        return x @ W['output_layer.output_projection.weight'].T + W['output_layer.output_projection.bias']

    def logits(self, logcqt):  # [frames,144] log-magnitude
        f = (logcqt - self.mean) / self.std
        n = f.shape[0]; pad = T - n % T
        f = np.pad(f, ((0, pad), (0, 0)))
        out = np.concatenate([self.window_logits(f[i:i + T].astype(np.float32)) for i in range(0, len(f), T)])
        return out[:n]

def cqt_lengths(sr=SR):
    Q = 1.0 / (2 ** (1 / BPO) - 1)
    f = FMIN * 2 ** (np.arange(NB) / BPO)
    return Q * sr / f, f

def cqt_chunk(y, sr=SR):
    """Ideal full-rate CQT (what librosa's multirate scheme approximates), frames centred at t*HOP."""
    L, f = cqt_lengths(sr)
    nfr = 1 + len(y) // HOP
    pad = int(np.ceil(L.max() / 2)) + 1
    yp = np.pad(y, pad, mode='reflect')
    C = np.zeros((NB, nfr), np.float32)
    for k in range(NB):
        l = L[k]; n = int(np.floor(l)); start = -n // 2  # librosa centres filter
        t = np.arange(n) + start
        win = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / n)  # periodic hann
        ker = win * np.exp(2j * np.pi * f[k] * t / sr)
        ker /= np.abs(ker).sum()
        idx = (np.arange(nfr) * HOP)[:, None] + t[None, :] + pad
        C[k] = np.abs(yp[idx] @ ker) * np.sqrt(l)
    return C

def features(y):
    """librosa pipeline in BTC: 10 s chunks, CQT each, concatenate, log(|C|+1e-6). Returns [frames,144]."""
    step = int(SR * 10.0); parts = []; s = 0
    while len(y) > s + step:
        parts.append(cqt_chunk(y[s:s + step])); s += step
    parts.append(cqt_chunk(y[s:]))
    return np.log(np.concatenate(parts, 1) + 1e-6).T
