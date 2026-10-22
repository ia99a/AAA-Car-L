import cv2, numpy as np, sys, os
g = cv2.imread('pattern/soft.jpg', 0).astype(np.float32) / 255
PAL = {  # الطرف الداكن (النقش)، الطرف الفاتح (الأرضية)
    'charcoal': ('#191919', '#2d2d2e'), 'navy': ('#0c1324', '#1c2947'), 'green': ('#08160f', '#173729'),
    'burgundy': ('#170a0d', '#3b161c'), 'bronze': ('#15110b', '#392d1d'), 'gold': ('#3b2f19', '#0e0e0f'),
    'graphite': ('#131519', '#292e36'),
}
def rgb(h): return np.array([int(h[i:i + 2], 16) for i in (5, 3, 1)], np.float32)  # BGR
def render(name, w, h):
    s = h / g.shape[0]
    img = cv2.resize(g, (round(g.shape[1] * s), h), interpolation=cv2.INTER_CUBIC if s > 1 else cv2.INTER_AREA)
    if img.shape[1] < w:  # أعرض من الصورة: نكبّر حسب العرض
        s = w / g.shape[1]; img = cv2.resize(g, (w, round(g.shape[0] * s)), interpolation=cv2.INTER_CUBIC)
    y0, x0 = (img.shape[0] - h) // 2, (img.shape[1] - w) // 2
    t = img[y0:y0 + h, x0:x0 + w][..., None]
    c0, c1 = (rgb(c) for c in PAL[name])
    out = c0 + t * (c1 - c0)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    d = np.sqrt((xx - w * .5) ** 2 + (yy - h * .42) ** 2) / (max(w, h) * .62)
    a = np.clip((d - .45) / .55, 0, 1) * 0.5
    return np.clip(out * (1 - a[..., None]), 0, 255).astype(np.uint8)
o = sys.argv[1]; os.makedirs(o, exist_ok=True)
rows = [cv2.resize(render(n, 1920, 720), (960, 360), interpolation=cv2.INTER_AREA) for n in PAL]
cv2.imwrite(o + '/sheet.png', np.vstack([np.vstack([r, np.full((8, 960, 3), 60, np.uint8)]) for r in rows]))
for n in PAL: cv2.imwrite(f'{o}/pattern-{n}-4k.jpg', render(n, 3840, 1440), [cv2.IMWRITE_JPEG_QUALITY, 93])
cv2.imwrite(o + '/orig_vs_charcoal.png', np.vstack([cv2.resize(cv2.imread('pattern/src.jpg'), (960, 320)), cv2.resize(render('charcoal', 1500, 500), (960, 320))]))
