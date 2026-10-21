# تنبيهات بلا موسيقى: كل نمط بدرجة صوت واحدة ثابتة، بلا لحن ولا آلات.
# الفرق بين الأنواع الثلاثة في عدد النبضات فقط: قبل الأذان ٢، الأذان ٣×٢، قبل الإقامة ٣ سريعة.
import numpy as np, wave, sys, subprocess, os
SR = 44100
rng = np.random.default_rng(3)

def env(n, a, r):
    e = np.ones(n); a = max(1, int(SR*a)); r = max(1, int(SR*r))
    e[:a] = np.linspace(0, 1, a); e[-r:] = np.minimum(e[-r:], np.linspace(1, 0, r)); return e

def sine(f, ms, a=.012, r=.012):
    n = int(SR*ms/1000); t = np.arange(n)/SR; return np.sin(2*np.pi*f*t)*env(n, a, r)

def ping(f, ms):  # نبضة تخفت تدريجيًا (سونار)
    n = int(SR*ms/1000); t = np.arange(n)/SR; return np.sin(2*np.pi*f*t)*np.exp(-t*7)*env(n, .004, .02)

def tick(ms=28):  # نقرة خفيفة مثل صوت الإشارة
    n = int(SR*ms/1000); t = np.arange(n)/SR
    x = rng.standard_normal(n)*np.exp(-t*260) + np.sin(2*np.pi*1400*t)*np.exp(-t*180)*.8
    return x*env(n, .001, .005)

def soft(f, ms):  # نبضة ناعمة بطيئة الصعود
    return sine(f, ms, a=.09, r=.12)

S = {  # الاسم: (مولد النبضة، طول النبضة، صمت قصير، صمت طويل)
    'pulse': (lambda ms: sine(1000, ms), 140, 160),
    'tick':  (lambda ms: tick(), 30, 230),
    'long':  (lambda ms: soft(700, ms), 480, 260),
    'sonar': (lambda ms: ping(1150, ms), 600, 120),
    'clear': (lambda ms: sine(1800, ms, .006, .006), 110, 110),
    'soft':  (lambda ms: soft(520, ms), 260, 220),
}
COUNT = {'soon': [2], 'adhan': [3, 3], 'iqama': [3]}

def build(style, kind):
    gen, on, off = S[style]
    on2 = on*.65 if kind == 'iqama' and style not in ('tick', 'sonar') else on
    off2 = off*.6 if kind == 'iqama' else off
    out = []
    for gi, cnt in enumerate(COUNT[kind]):
        for i in range(cnt):
            out += [gen(on2), np.zeros(int(SR*off2/1000))]
        out.append(np.zeros(int(SR*.45)))
    x = np.concatenate(out)
    x = x/np.max(np.abs(x))
    rms = np.sqrt(np.mean(x[np.abs(x) > .02]**2)); x = x*min(.55/rms, 1)*.8  # مستوى متقارب
    return np.clip(x, -.85, .85)

D = sys.argv[1]; os.makedirs(D, exist_ok=True)
for s in S:
    for k in COUNT:
        x = build(s, k); f = f'{D}/{s}_{k}'
        w = wave.open(f+'.wav', 'w'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((x*32767).astype('<i2').tobytes()); w.close()
        subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-i', f+'.wav', '-c:a', 'libvorbis', '-q:a', '4', f+'.ogg'], check=True)
