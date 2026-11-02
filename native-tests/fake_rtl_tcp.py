#!/usr/bin/env python3
"""Sahte rtl_tcp sunucusu. LO frekansına göre bir IQ dosyasını (istasyon) döngüyle yayınlar,
istasyon yoksa gürültü verir. Gerçek zamanlı hızda (2.048 MS/s, 4.096 MB/s) akıtır.
kullanım: fake_rtl_tcp.py PORT  FREQ_HZ=dosya.u8 [FREQ_HZ=dosya.u8 ...]"""
import socket, struct, sys, threading, time, numpy as np

port=int(sys.argv[1]); stations={}
for a in sys.argv[2:]:
    f,p=a.split('='); stations[int(float(f))]=np.fromfile(p,dtype=np.uint8)
state={'lo':100000000}
rng=np.random.default_rng(5)
noise=np.clip(np.round(127.5+rng.standard_normal(2*1048576)*2.0),0,255).astype(np.uint8)  # ~1 LSB gürültü
srv=socket.socket(); srv.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); srv.bind(('127.0.0.1',port)); srv.listen(1)
print("hazir",flush=True)
def reader(c):
    buf=b''
    while True:
        d=c.recv(256)
        if not d: return
        buf+=d
        while len(buf)>=5:
            cmd,par=struct.unpack('>BI',buf[:5]); buf=buf[5:]
            if cmd==0x01: state['lo']=par; print("freq",par,flush=True)
            elif cmd==0x7e: print("exit komutu",flush=True)
while True:
    c,_=srv.accept(); print("baglandi",flush=True)
    c.sendall(b'RTL0'+struct.pack('>II',5,29))
    threading.Thread(target=reader,args=(c,),daemon=True).start()
    pos={}; t0=time.time(); sent=0; CH=32768
    try:
        while True:
            lo=state['lo']; src=noise
            for f,arr in stations.items():
                if abs(lo-f)<20000: src=arr; break
            p=pos.get(id(src),0)
            if p+CH>len(src): p=0
            c.sendall(src[p:p+CH].tobytes()); pos[id(src)]=p+CH; sent+=CH
            ahead=sent/4096000.0-(time.time()-t0)
            if ahead>0: time.sleep(ahead)
    except (BrokenPipeError,ConnectionResetError,OSError):
        print("kapandi",flush=True); c.close()
