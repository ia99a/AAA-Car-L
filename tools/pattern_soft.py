# الخلفية الأصلية كما هي (نفس النقش ونفس النعومة): نزيل فقط اختلاف الإضاءة (التعتيم يُضاف في التطبيق)
# ونخفف تكتل JPEG، ثم نمدّ الدرجات على 0..255 ليُلوَّن في التطبيق بأي لونين.
import cv2, numpy as np, sys
from scipy.ndimage import gaussian_filter
src = cv2.imread(sys.argv[1], cv2.IMREAD_GRAYSCALE).astype(np.float32)
flat = src / gaussian_filter(src, 80) * np.median(src)
flat = cv2.GaussianBlur(flat, (0, 0), 0.8)
lo, hi = np.percentile(flat, 0.5), np.percentile(flat, 99.5)
print('levels', round(lo, 1), round(hi, 1), 'original median', np.median(src))
out = np.clip((flat - lo) / (hi - lo) * 255, 0, 255).astype(np.uint8)
cv2.imwrite(sys.argv[2], out, [cv2.IMWRITE_JPEG_QUALITY, 95])
