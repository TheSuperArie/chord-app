"""Read a legacy (pre-1.6) torch checkpoint into numpy without torch."""
import pickle, struct, collections
import numpy as np
DT = {'FloatStorage': np.float32, 'DoubleStorage': np.float64, 'LongStorage': np.int64, 'IntStorage': np.int32,
      'HalfStorage': np.float16, 'ByteStorage': np.uint8, 'BoolStorage': np.bool_}
class StorageType:
    def __init__(s, n): s.name = n
class Storage:
    def __init__(s, key, dtype, size): s.key, s.dtype, s.size, s.data = key, dtype, size, None
def rebuild(storage, offset, size, stride, *a):
    return ('T', storage, offset, tuple(size), tuple(stride))
class U(pickle.Unpickler):
    def __init__(s, f, stor): super().__init__(f); s.stor = stor
    def find_class(s, mod, name):
        if mod.startswith('torch') and name.endswith('Storage'): return StorageType(name)
        if name in ('_rebuild_tensor_v2', '_rebuild_tensor'): return rebuild
        if mod == 'collections' and name == 'OrderedDict': return collections.OrderedDict
        if name == '_rebuild_parameter': return lambda data, rg, hooks: data
        return super().find_class(mod, name)
    def persistent_load(s, pid):
        typ, st, key, loc, size, view = pid
        if key not in s.stor: s.stor[key] = Storage(key, DT[st.name], size)
        return s.stor[key]
def load(path):
    f = open(path, 'rb')
    for _ in range(3): pickle.load(f)
    stor = {}
    obj = U(f, stor).load()
    keys = pickle.load(f)
    for k in keys:
        n = struct.unpack('<q', f.read(8))[0]
        st = stor[k]
        st.data = np.frombuffer(f.read(n * np.dtype(st.dtype).itemsize), st.dtype)
    def conv(o):
        if isinstance(o, tuple) and o and o[0] == 'T':
            _, st, off, size, stride = o
            it = np.dtype(st.dtype).itemsize
            return np.lib.stride_tricks.as_strided(st.data[off:], size, [x * it for x in stride]).copy()
        if isinstance(o, dict): return {k: conv(v) for k, v in o.items()}
        if isinstance(o, (list, tuple)): return type(o)(conv(x) for x in o)
        return o
    return conv(obj)
if __name__ == '__main__':
    ck = load('/home/claude/jayg996/btc-ismir19/test/btc_model_large_voca.pt')
    print(ck.keys())
    print(type(ck['mean']), np.shape(ck['mean']), np.shape(ck['std']))
    for k, v in list(ck['model'].items())[:40]: print(k, v.shape)
    print(len(ck['model']))
