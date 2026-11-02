#!/usr/bin/env python3
import numpy as np, sys
fs=32000
x=np.fromfile(sys.argv[1],dtype=np.int16).reshape(-1,2).astype(np.float64)/32768.0
x=x[int(1.5*fs):]
def level(sig,f):
    n=len(sig); w=np.hanning(n); S=np.abs(np.fft.rfft(sig*w))/(w.sum()/2)
    k=int(round(f*n/fs)); return S[max(0,k-3):k+4].max()
def db(v): return 20*np.log10(max(v,1e-12))
L,R=x[:,0],x[:,1]
tones=[float(a) for a in sys.argv[2:]] if len(sys.argv)>2 else [1000.0,2500.0]
for name,ch in (('L',L),('R',R)):
    print(name,'rms=%.4f'%np.sqrt((ch**2).mean()), ' '.join('%.0fHz:%.1fdB'%(f,db(level(ch,f))) for f in tones),
          ' 13kHz(pilot alias):%.1fdB'%db(level(ch,13000)))
