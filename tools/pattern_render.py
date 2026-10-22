import cv2, numpy as np, sys
m = cv2.imread('mask2.png', 0).astype(np.float32) / 255
H, W = m.shape
PAL = {  # الخلفية، النقش
    'charcoal': ('#232427', '#151618'), 'navy': ('#16203a', '#0b1222'), 'green': ('#0f2a1f', '#07170f'),
    'burgundy': ('#2c1216', '#1a090c'), 'bronze': ('#2b2218', '#18120b'), 'gold': ('#0e0e0f', '#3b301d'),
    'graphite': ('#23282f', '#15191e'),
}
def hexrgb(h): return np.array([int(h[i:i + 2], 16) for i in (1, 3, 5)], np.float32)[::-1]  # BGR
def render(name, w, h, period_h):
    s = period_h / H
    tile = cv2.resize(m, (round(W * s), round(H * s)), interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_CUBIC)
    th, tw = tile.shape
    ny, nx = h // th + 2, w // tw + 2
    big = np.tile(tile, (ny, nx))
    ox, oy = (nx * tw - w) // 2, (ny * th - h) // 2
    mm = np.clip(big[oy:oy + h, ox:ox + w], 0, 1)[..., None]
    bg, fg = (hexrgb(c) for c in PAL[name])
    img = bg * (1 - mm) + fg * mm
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    r = np.sqrt(((xx - w * .5) / (w * .62)) ** 2 + ((yy - h * .42) / (h * .9)) ** 2)
    vig = 1.12 - 0.42 * np.clip(r, 0, 1.3) ** 1.6
    img = img * vig[..., None]
    noise = np.random.default_rng(1).normal(0, 0.9, (h, w, 1))  # حبيبات خفيفة تمنع التدرّج المتقطع
    return np.clip(img + noise, 0, 255).astype(np.uint8)
if __name__ == '__main__':
    out = sys.argv[1]
    rows = []
    for n in PAL:
        rows.append(cv2.resize(render(n, 1920, 720, 330), (960, 360), interpolation=cv2.INTER_AREA))
    cv2.imwrite(out + '/sheet.png', np.vstack([np.vstack([r, np.full((8, 960, 3), 60, np.uint8)]) for r in rows]))
    for n in PAL:
        cv2.imwrite(f'{out}/pattern-{n}-4k.jpg', render(n, 3840, 1440, 660), [cv2.IMWRITE_JPEG_QUALITY, 94])
