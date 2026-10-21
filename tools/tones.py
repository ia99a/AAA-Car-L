# ستة أساليب (الجرس الناعم وخمسة أخرى) نغمات، لكل أسلوب ثلاث نغمات: قبل الأذان (soon)، الأذان (adhan)، قبل الإقامة (iqama)
import numpy as np, wave, sys, os
SR = 44100
rng = np.random.default_rng(7)
D = sys.argv[1]

def norm(x, peak=0.72):
    fade = np.ones_like(x); n = int(SR*0.3); fade[-n:] = np.linspace(1, 0, n) ** 2
    x = x * fade
    return x / (np.max(np.abs(x)) + 1e-9) * peak

def reverb(x, amt=0.22):
    out = x.copy()
    for d, g in [(0.031, .5), (0.047, .42), (0.071, .35), (0.113, .26), (0.167, .18), (0.241, .12)]:
        k = int(SR*d); tmp = np.zeros_like(x); tmp[k:] = x[:-k] * g; out += tmp * amt
    return out

def place(total, parts):
    out = np.zeros(int(SR*total))
    for start, sig in parts:
        i = int(SR*start); n = min(len(sig), len(out)-i); out[i:i+n] += sig[:n]
    return out

t_of = lambda d: np.arange(int(SR*d))/SR

# ── ١) قانون: وتر منقور (كاربلس-سترونغ) على مقام حجاز ──
def pluck(f, dur, bright=0.5, amp=1.0):
    n = int(SR*dur); p = int(SR/f)
    buf = rng.uniform(-1, 1, p) * amp
    # تنعيم أولي ليكون الوتر دافئًا لا حادًا
    for _ in range(int(3*(1-bright))+1): buf = 0.5*(buf + np.roll(buf, 1))
    out = np.zeros(n)
    for i in range(n):
        out[i] = buf[i % p]
        buf[i % p] = 0.996 * 0.5 * (buf[i % p] + buf[(i+1) % p])
    return out * np.exp(-t_of(dur)*1.1)

# ── ٢) ناي: نفَس هوائي ناعم مع اهتزاز خفيف ──
def ney(f, dur, amp=1.0):
    t = t_of(dur)
    vib = 1 + 0.006*np.sin(2*np.pi*5.2*t) * np.clip(t/0.4, 0, 1)
    ph = 2*np.pi*f*np.cumsum(vib)/SR
    tone = np.sin(ph) + 0.25*np.sin(2*ph) + 0.08*np.sin(3*ph)
    breath = rng.normal(0, 1, len(t)); breath = np.convolve(breath, np.ones(30)/30, 'same') * 0.18
    env = np.clip(t/0.18, 0, 1) * np.exp(-np.clip(t-0.35, 0, None)*1.6)
    return amp * env * (tone + breath)

# ── ٣) أجراس ريح: لمعان معدني عالٍ ──
def chimebar(f, dur, amp=1.0):
    t = t_of(dur)
    partials = [(1, 1, 1.4), (2.76, .45, 2.6), (5.40, .25, 4.0), (8.93, .12, 6.0)]
    return amp * sum(a*np.sin(2*np.pi*f*r*t)*np.exp(-t*d) for r, a, d in partials) * (1-np.exp(-t*400))

# ── ٤) ماريمبا فاخرة (أسلوب تنبيهات السيارات الحديثة) ──
def marimba(f, dur, amp=1.0):
    t = t_of(dur)
    body = np.sin(2*np.pi*f*t)*np.exp(-t*4.5) + 0.5*np.sin(2*np.pi*f*4*t)*np.exp(-t*14) + 0.15*np.sin(2*np.pi*f*10*t)*np.exp(-t*40)
    return amp * body * (1-np.exp(-t*600))

# ── ٥) كريستال: جرس زجاجي ناعم بطبقتين ──
def crystal(f, dur, amp=1.0):
    t = t_of(dur)
    a = np.sin(2*np.pi*f*t) + 0.6*np.sin(2*np.pi*f*1.0035*t)  # نبضة رقيقة بين طبقتين
    b = 0.3*np.sin(2*np.pi*f*2*t)*np.exp(-t*2.5) + 0.12*np.sin(2*np.pi*f*3*t)*np.exp(-t*5)
    return amp * (a+b) * np.exp(-t*1.8) * (1-np.exp(-t*90))

def save(name, x):
    with wave.open(os.path.join(D, name+'.wav'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x*32767).astype('<i2').tobytes())

# نوتات (هرتز)
D4,E4,F4,G4,A4,Bb4,B4 = 293.66,329.63,349.23,392.0,440.0,466.16,493.88
C5,Db5,D5,Eb5,E5,F5,G5,A5,C6,E6,G6 = 523.25,554.37,587.33,622.25,659.25,698.46,783.99,880.0,1046.5,1318.5,1568.0

styles = {
 # حجاز على ري: ري، مي♭، فا#، صول — طابع عربي
 'qanun': {
   'soon':  reverb(place(2.6, [(0, pluck(D5,2.4,.6)), (0.28, pluck(G5,2.3,.6,.9))]), .3),
   'adhan': reverb(place(3.6, [(0, pluck(D4*2,3.2,.6)), (0.26, pluck(Eb5,3.0,.6,.9)), (0.52, pluck(369.99*2,2.9,.6,.9)), (0.86, pluck(G5,2.7,.6,1.0))]), .3),
   'iqama': reverb(place(2.6, [(0, pluck(G5,2.4,.6)), (0.3, pluck(D5,2.3,.6,.95))]), .3)},
 'ney': {
   'soon':  reverb(place(2.6, [(0, ney(A4,1.2,.9)), (0.7, ney(D5,1.6,.9))]), .35),
   'adhan': reverb(place(3.8, [(0, ney(D5,1.0,.9)), (0.62, ney(E5,1.0,.85)), (1.24, ney(F5,1.0,.85)), (1.9, ney(A5,1.8,.9))]), .35),
   'iqama': reverb(place(2.6, [(0, ney(D5,1.1,.9)), (0.7, ney(A4,1.6,.9))]), .35)},
 'wind': {
   'soon':  reverb(place(2.6, [(0, chimebar(E6,2.4,.8)), (0.22, chimebar(C6,2.3,.7))]), .25),
   'adhan': reverb(place(3.8, [(s, chimebar(f,3.0,a)) for s,f,a in [(0,C6,.7),(0.18,E6,.6),(0.34,G6,.55),(0.55,C6*2,.4),(0.8,G6,.45),(1.1,E6,.5)]]), .25),
   'iqama': reverb(place(2.6, [(0, chimebar(G6,2.4,.7)), (0.24, chimebar(E6,2.3,.7))]), .25)},
 'marimba': {
   'soon':  reverb(place(2.0, [(0, marimba(E5,1.6)), (0.16, marimba(A5,1.6))]), .2),
   'adhan': reverb(place(2.8, [(0, marimba(C5,2.0)), (0.17, marimba(E5,2.0,.9)), (0.34, marimba(G5,2.0,.9)), (0.55, marimba(C6,2.0,.8))]), .2),
   'iqama': reverb(place(2.0, [(0, marimba(A5,1.6)), (0.17, marimba(E5,1.6))]), .2)},
 'crystal': {
   'soon':  reverb(place(2.6, [(0, crystal(A5,2.4,.8)), (0.4, crystal(E6/2*2,2.2,.6))]), .3),
   'adhan': reverb(place(3.8, [(0, crystal(E5,3.4,.8)), (0.5, crystal(A5,3.2,.7)), (1.0, crystal(E6/1,2.8,.5))]), .3),
   'iqama': reverb(place(2.6, [(0, crystal(A5,2.4,.75)), (0.42, crystal(E5,2.2,.8))]), .3)},
}
for st, d in styles.items():
    for kind, sig in d.items(): save(f'{st}_{kind}', norm(sig))
print('ok', len(styles)*3)
