# Builds rsrc_windows_amd64.syso: app icon + version info, as a COFF object the Go linker picks up.
import io, struct, sys
from PIL import Image, ImageDraw, ImageFont
out = sys.argv[1]

def icon(sz):
    S = sz * 4
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * .22), fill=(31, 111, 235, 255))
    # droplet
    cx, cy, r = S * .5, S * .60, S * .24
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill='white')
    d.polygon([(cx - r * .93, cy - r * .35), (cx, S * .14), (cx + r * .93, cy - r * .35)], fill='white')
    # plus sign
    t, L = S * .045, S * .11
    d.rectangle([cx - L, cy - t, cx + L, cy + t], fill=(31, 111, 235, 255))
    d.rectangle([cx - t, cy - L, cx + t, cy + L], fill=(31, 111, 235, 255))
    im = im.resize((sz, sz), Image.LANCZOS); b = io.BytesIO(); im.save(b, 'PNG'); return b.getvalue(), im
sizes = [16, 24, 32, 48, 64, 256]
imgs = [icon(s) for s in sizes]
imgs[-1][1].save(out.rsplit('/', 1)[0] + '/icon-preview.png')

def vs_versioninfo():
    def node(key, value=b'', wtype=0, children=b'', vlen=None):
        k = (key + '\0').encode('utf-16le')
        hdr_len = 6 + len(k); pad1 = (4 - hdr_len % 4) % 4
        body = k + b'\0' * pad1 + value
        pad2 = (4 - len(body + struct.pack('<HHH', 0, 0, 0)) % 4) % 4 if children else 0
        body += b'\0' * ((4 - (6 + len(body)) % 4) % 4) + children if children else b''
        if vlen is None: vlen = len(value)
        return struct.pack('<HHH', 6 + len(body), vlen, wtype) + body
    def s(key, val):
        v = (val + '\0').encode('utf-16le'); n = node(key, v, 1, vlen=len(val) + 1)
        return n + b'\0' * ((4 - len(n) % 4) % 4)
    ver = (1, 6, 0, 0)
    ffi = struct.pack('<13I', 0xFEEF04BD, 0x10000, (ver[0] << 16) | ver[1], (ver[2] << 16) | ver[3], (ver[0] << 16) | ver[1], (ver[2] << 16) | ver[3], 0x3F, 0, 0x40004, 1, 0, 0, 0)
    strs = b''.join(s(k, v) for k, v in [('CompanyName', 'NeQo'), ('FileDescription', 'Insulin Diary Power Control'), ('ProductName', 'Insulin Diary Power Control'), ('FileVersion', '1.6.0.0'), ('ProductVersion', '1.6.0.0'), ('OriginalFilename', 'Insulin Diary Power Control.exe'), ('LegalCopyright', 'No copyright - public domain (The Unlicense). Free software by NeQo.')])
    table = node('040904B0', children=strs)
    sfi = node('StringFileInfo', children=table, wtype=1)
    var = node('Translation', struct.pack('<HH', 0x0409, 0x04B0), 0)
    vfi = node('VarFileInfo', children=var, wtype=1)
    return node('VS_VERSION_INFO', ffi, 0, children=sfi + vfi, vlen=len(ffi))

# resources: (type, id, data)
res = [(3, i + 1, png) for i, (png, _) in enumerate(imgs)]
grp = struct.pack('<HHH', 0, 1, len(imgs)) + b''.join(
    struct.pack('<BBBBHHIH', s % 256, s % 256, 0, 0, 1, 32, len(p), i + 1) for i, ((p, _), s) in enumerate(zip(imgs, sizes)))
res.append((14, 1, grp))
res.append((16, 1, vs_versioninfo()))
types = sorted(set(t for t, _, _ in res))

# layout: root dir, type dirs, name dirs (lang level), data entries, then data
def dirhdr(n): return struct.pack('<IIHHHH', 0, 0, 0, 0, 0, n)
root_sz = 16 + 8 * len(types)
type_dirs = {t: sorted([r for r in res if r[0] == t], key=lambda r: r[1]) for t in types}
off = root_sz; type_off = {}
for t in types: type_off[t] = off; off += 16 + 8 * len(type_dirs[t])
lang_off = {}
for t in types:
    for r in type_dirs[t]: lang_off[(t, r[1])] = off; off += 16 + 8
entry_off = {}
for t in types:
    for r in type_dirs[t]: entry_off[(t, r[1])] = off; off += 16
data_off = {}
for t in types:
    for r in type_dirs[t]:
        off = (off + 7) & ~7; data_off[(t, r[1])] = off; off += len(r[2])
sec = bytearray(off); relocs = []
def put(o, b): sec[o:o + len(b)] = b
put(0, dirhdr(len(types)) + b''.join(struct.pack('<II', t, 0x80000000 | type_off[t]) for t in types))
for t in types:
    put(type_off[t], dirhdr(len(type_dirs[t])) + b''.join(struct.pack('<II', r[1], 0x80000000 | lang_off[(t, r[1])]) for r in type_dirs[t]))
    for r in type_dirs[t]:
        k = (t, r[1])
        put(lang_off[k], dirhdr(1) + struct.pack('<II', 0x0409, entry_off[k]))
        put(entry_off[k], struct.pack('<IIII', data_off[k], len(r[2]), 0, 0)); relocs.append(entry_off[k])
        put(data_off[k], r[2])
sec = bytes(sec); sec += b'\0' * ((4 - len(sec) % 4) % 4)
hdr_sz = 20 + 40; raw_ptr = hdr_sz; rel_ptr = raw_ptr + len(sec); sym_ptr = rel_ptr + 10 * len(relocs)
fh = struct.pack('<HHIIIHH', 0x8664, 1, 0, sym_ptr, 1, 0, 0)
sh = struct.pack('<8sIIIIIIHHI', b'.rsrc', 0, 0, len(sec), raw_ptr, rel_ptr, 0, len(relocs), 0, 0x40000040)
rel = b''.join(struct.pack('<IIH', r, 0, 3) for r in relocs)  # IMAGE_REL_AMD64_ADDR32NB
sym = struct.pack('<8sIhHBB', b'.rsrc', 0, 1, 0, 3, 0) + struct.pack('<I', 4)
open(out, 'wb').write(fh + sh + sec + rel + sym)
print('ok', len(res), 'resources')
