# يستبدل تاج النخلة الضبابي في القناع بسعف مرسوم ناعم الحواف
import cv2, numpy as np
m = cv2.imread('mask.png', 0).astype(np.float32) / 255
H, W = m.shape
S = 4
big = cv2.resize(m, (W * S, H * S), interpolation=cv2.INTER_CUBIC)
CROWN = [(662, 350), (662 - W // 2, 350 + H // 2)]
def wraps(x, y):
    for dx in (-W, 0, W):
        for dy in (-H, 0, H):
            yield x + dx, y + dy
# ١) مسح التاج القديم (المنطقة فوق رأس الجذع فقط)
yy, xx = np.mgrid[0:H * S, 0:W * S] / S
for cx, cy in CROWN:
    for x, y in wraps(cx, cy):
        inside = ((xx - x) / 74) ** 2 + ((yy - (y - 50)) / 58) ** 2 < 1
        big[inside & (yy < y - 2)] = 0
# ٢) رسم السعف
def frond(img, sx, sy, ang, L, droop, w):
    a = np.radians(ang); d = np.radians(droop)
    P0 = np.array([sx, sy]); P1 = P0 + L * 0.55 * np.array([np.sin(a), -np.cos(a)])
    P2 = P0 + L * np.array([np.sin(a + d), -np.cos(a + d)])
    ts = np.linspace(0, 0.97, 60)
    pts = [(1 - t) ** 2 * P0 + 2 * (1 - t) * t * P1 + t * t * P2 for t in ts]
    left, right = [], []
    for i, t in enumerate(ts):
        p = pts[i]; q = pts[min(i + 1, 59)] - pts[max(i - 1, 0)]
        n = np.array([-q[1], q[0]]); n /= (np.linalg.norm(n) + 1e-9)
        ww = max(w * (np.sin(np.pi * min(1, t * 1.1 + .06)) ** .6) * (1 - .35 * t), 1.4)
        left.append(p + n * ww / 2); right.append(p - n * ww / 2)
    poly = np.array(left + right[::-1]) * S
    cv2.fillPoly(img, [poly.astype(np.int32)], 1.0, lineType=cv2.LINE_AA)
FRONDS = [  # الزاوية من العمودي، الطول، الانحناء للأسفل، العرض
    (-10, 58, -16, 14), (10, 58, 16, 14),
    (-34, 66, -34, 14), (34, 66, 34, 14),
    (-58, 72, -46, 13), (58, 72, 46, 13),
    (-82, 72, -52, 12), (82, 72, 52, 12),
    (-106, 60, -42, 11), (106, 60, 42, 11),
]
layer = np.zeros_like(big)
for cx, cy in CROWN:
    for x, y in wraps(cx, cy - 8):
        for ang, L, dr, w in FRONDS:
            frond(layer, x, y, ang, L, dr, w)
        cv2.ellipse(layer, (int(x * S), int((y + 2) * S)), (int(11 * S), int(8 * S)), 0, 0, 360, 1.0, -1, cv2.LINE_AA)
big = np.maximum(big, layer)
out = cv2.resize(big, (W, H), interpolation=cv2.INTER_AREA)
cv2.imwrite('mask2.png', np.clip(out * 255, 0, 255).astype(np.uint8))
c = out[240:640, 520:810]
cv2.imwrite('emblem2.png', cv2.resize(255 - np.clip(c * 255, 0, 255).astype(np.uint8), None, fx=2, fy=2, interpolation=cv2.INTER_LINEAR))
