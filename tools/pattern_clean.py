# من البلاطة المتوسطة إلى قناع نقش نظيف: تناظر أفقي، ثم تنعيم وحدّ ناعم بدقة مضاعفة
import numpy as np, cv2, sys
t = cv2.imread('tile4x.png', 0).astype(np.float32)
H, W = t.shape
lo, hi = np.percentile(t, 2), np.percentile(t, 98)
t = np.clip((t - lo) / (hi - lo), 0, 1)
# محور التناظر الأفقي: أفضل إزاحة دائرية للصورة المعكوسة
f = t[:, ::-1]
errs = [(np.mean((np.roll(f, s, 1) - t) ** 2), s) for s in range(W)]
e, s = min(errs); print('mirror err', round(e, 4), 'vs no-mirror', round(np.mean((f - t) ** 2), 4), 'shift', s)
t = (t + np.roll(f, s, 1)) / 2
# دقة مضاعفة، تنعيم، ثم حد ناعم (القناع: ١ = النقش الداكن)
u = cv2.resize(np.tile(t, (3, 3)), None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)
u = cv2.bilateralFilter(u, 9, 0.25, 4)
u = cv2.GaussianBlur(u, (0, 0), 2.2)
thr = float(sys.argv[1]) if len(sys.argv) > 1 else 0.5
m = 1 - np.clip((u - (thr - 0.08)) / 0.16, 0, 1)
m = m * m * (3 - 2 * m)
m = m[2 * H:4 * H, 2 * W:4 * W]  # البلاطة الوسطى (تكرار سليم)
m = cv2.resize(m, (W, H), interpolation=cv2.INTER_AREA)
# إزالة البقع الصغيرة
mb = (m > 0.5).astype(np.uint8)
n, lab, stats, _ = cv2.connectedComponentsWithStats(np.tile(mb, (3, 3)), 8)
print('motif coverage', round(m.mean(), 3))
cv2.imwrite('mask.png', (m * 255).astype(np.uint8))
