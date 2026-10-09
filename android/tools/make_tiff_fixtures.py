"""Writes the TIFF test files in app/src/test/resources/tiff, each with the 8-bit pixels an
independent reader (tifffile) gets from it.

    pip install numpy tifffile imagecodecs
    python tools/make_tiff_fixtures.py app/src/test/resources/tiff
"""
import sys, numpy as np, tifffile
out = sys.argv[1]
W, H = 37, 23
rng = np.random.default_rng(3)
def img(dtype, ch, lo=0.0, hi=1.0):
    y, x = np.mgrid[0:H, 0:W]
    base = np.stack([(np.sin(x/5+c)+np.cos(y/3)+2)/4 for c in range(ch)], -1)
    base = np.clip(base + rng.normal(0, 0.02, base.shape), 0, 1)
    if np.issubdtype(np.dtype(dtype), np.floating):
        a = (base*(hi-lo)+lo).astype(dtype)
    else:
        info = np.iinfo(dtype); a = np.rint(base*(info.max-info.min)+info.min).astype(dtype)
    return a[..., 0] if ch == 1 else a
def to8(a, bits=None):
    a = np.asarray(a)
    if a.dtype.kind == 'f':
        x = np.floor(np.clip(np.nan_to_num(a.astype(np.float64)), 0, 1)*65535+0.5).astype(np.int64); m = 65535
    elif a.dtype == np.int16: x = (a.astype(np.int64) + 32768); m = 65535
    elif a.dtype == np.uint32: x = a.astype(np.int64) >> 16; m = 65535
    else:
        x = a.astype(np.int64); m = (1 << (bits or a.dtype.itemsize*8)) - 1
    return ((x*255 + m//2)//m).astype(np.uint8) if m != 255 else x.astype(np.uint8)
cases = {
 'rgb_u8_strips': dict(a=img(np.uint8,3), kw=dict(rowsperstrip=5)),
 'rgb_u16_lzw_pred2_tiled': dict(a=img(np.uint16,3), kw=dict(compression='lzw', predictor=True, tile=(16,16))),
 'rgb_u16_planar_deflate': dict(a=img(np.uint16,3), kw=dict(compression='zlib', planarconfig='separate', rowsperstrip=7)),
 'rgb_f32_deflate_pred3': dict(a=img(np.float32,3,-0.1,1.1), kw=dict(compression='zlib', predictor='floatingpoint', rowsperstrip=8)),
 'rgb_f32_pred3_bigendian': dict(a=img(np.float32,3), kw=dict(compression='zlib', predictor='floatingpoint', byteorder='>')),
 'rgb_f16_none': dict(a=img(np.float16,3), kw=dict()),
 'gray_f64_none': dict(a=img(np.float64,1), kw=dict()),
 'rgb_u32_none': dict(a=img(np.uint32,3), kw=dict()),
 'rgb_u32_deflate_pred2': dict(a=img(np.uint32,3), kw=dict(compression='zlib', predictor=True)),
 'gray_i16_none': dict(a=img(np.int16,1), kw=dict()),
 'bigtiff_rgb_u8_deflate_tiled': dict(a=img(np.uint8,3), kw=dict(bigtiff=True, compression='zlib', tile=(16,16))),
 'bigtiff_be_rgb_u16_pred2': dict(a=img(np.uint16,3), kw=dict(bigtiff=True, byteorder='>', compression='lzw', predictor=True)),
 'gray_u4_packed': dict(a=(img(np.uint8,1)>>4).astype(np.uint8), kw=dict(bitspersample=4), bits=4),
 'gray_u12_packed': dict(a=(img(np.uint16,1)>>4).astype(np.uint16), kw=dict(bitspersample=12), bits=12),
 'rgb_jpeg_ycbcr': dict(a=img(np.uint8,3), kw=dict(compression='jpeg', compressionargs={'level': 95}, photometric='ycbcr', rowsperstrip=16), lossy=True),
 'rgb_jpeg_tiled_rgb': dict(a=img(np.uint8,3), kw=dict(compression='jpeg', compressionargs={'level': 95}, photometric='rgb', tile=(16,16)), lossy=True),
}
for name, c in cases.items():
    path = f'{out}/{name}.tif'
    kw = dict(c['kw'])
    if 'photometric' not in kw: kw['photometric'] = 'rgb' if c['a'].ndim == 3 else 'minisblack'
    try:
        data = c['a']
        if kw.get('planarconfig') == 'separate': data = np.ascontiguousarray(np.moveaxis(data, -1, 0))
        tifffile.imwrite(path, data, **kw)
    except Exception as e:
        print('SKIP', name, e); continue
    ref = tifffile.imread(path)  # what an independent reader sees
    if kw.get('planarconfig') == 'separate': ref = np.moveaxis(ref, 0, -1)
    assert ref.shape[:2] == (H, W), (name, ref.shape)
    exp = to8(ref if c.get('lossy') else c['a'], c.get('bits'))
    open(f'{out}/{name}.expected', 'wb').write(np.ascontiguousarray(exp).tobytes())
    with tifffile.TiffFile(path) as t:
        p = t.pages[0]
        print(name, 'big' if t.is_bigtiff else '', p.compression.name, p.predictor, p.bitspersample, p.sampleformat, p.photometric, p.planarconfig, p.is_tiled, 'jpegtables' if p.jpegtables is not None else '')
