import numpy as np, cv2, sys
from scipy.ndimage import gaussian_filter
src = cv2.imread(sys.argv[1], cv2.IMREAD_GRAYSCALE).astype(np.float32)
H0, W0 = src.shape
flat = src / gaussian_filter(src, 60) * src.mean()          # إزالة التظليل/الإضاءة غير المتساوية
def stack(px, py, S, W, H, x0=0.0, y0=0.0):
    u = (np.arange(W) / W)[None, :]; v = (np.arange(H) / H)[:, None]
    samples = []
    for coset in [(0, 0), (0.5, 0.5)]:
        for i in range(-1, int(W0 / px) + 2):
            for j in range(-1, int(H0 / py) + 2):
                X = x0 + (u + i + coset[0]) * px + 0 * v
                Y = y0 + (v + j + coset[1]) * py + 0 * u
                ok = (X > 2) & (X < W0 - 3) & (Y > 2) & (Y < H0 - 3)
                if ok.mean() < 0.05: continue
                r = cv2.remap(flat, X.astype(np.float32), Y.astype(np.float32), cv2.INTER_CUBIC)
                r[~ok] = np.nan
                samples.append(r)
    return np.array(samples)
def score(px, py):
    s = stack(px, py, 1, 115, 115)
    return s.std(0).mean(), len(s)
if sys.argv[2] == 'search':
    best = []
    for px in np.arange(229.0, 230.6, 0.1):
        for py in np.arange(228.0, 230.1, 0.1):
            sc, n = score(px, py); best.append((sc, px, py, n))
    best.sort(); print(best[:5])
else:
    px, py, scale = float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5])
    W, H = round(px * scale), round(py * scale)
    s = stack(px, py, scale, W, H)
    t = np.nanmedian(s, 0); print('min coverage', np.isfinite(s).sum(0).min())
    print('samples', len(s), 'tile', W, H)
    cv2.imwrite(sys.argv[2], np.clip(t, 0, 255).astype(np.uint8))
