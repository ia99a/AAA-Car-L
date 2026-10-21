# نغمة تنبيه بسيطة غير موسيقية: نبضات بنفس الدرجة، بلا لحن ولا آلات ولا صدى
import numpy as np, wave, sys, subprocess
SR=44100; F=1000
def pulse(ms):
    n=int(SR*ms/1000); t=np.arange(n)/SR; x=np.sin(2*np.pi*F*t)
    a=int(SR*0.012); env=np.ones(n); env[:a]=np.linspace(0,1,a); env[-a:]=np.linspace(1,0,a); return x*env
def seq(pattern):  # [(طول النبضة, الصمت بعدها)] بالملّي
    out=[]
    for on,off in pattern: out+=[pulse(on),np.zeros(int(SR*off/1000))]
    return np.concatenate(out)
P={'soon':[(140,160),(140,300)],
   'adhan':[(140,160),(140,160),(140,600),(140,160),(140,160),(140,300)],
   'iqama':[(90,90),(90,90),(90,300)]}
D=sys.argv[1]
for k,p in P.items():
    x=seq(p); x=x/np.max(np.abs(x))*0.5
    w=wave.open(f'{D}/beep_{k}.wav','w'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((x*32767).astype('<i2').tobytes()); w.close()
    for ext,args in [('ogg',['-c:a','libvorbis','-q:a','4']),('mp3',['-b:a','128k'])]:
        subprocess.run(['ffmpeg','-loglevel','error','-y','-i',f'{D}/beep_{k}.wav',*args,f'{D}/beep_{k}.{ext}'],check=True)
