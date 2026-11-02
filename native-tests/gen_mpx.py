#!/usr/bin/env python3
"""Sentetik FM kompozit (MPX) üretici: stereo ses + 19 kHz pilot + RDS (PS + RadioText).
Çıktı: float32 ham dosya, 256 kS/s. RDS, pilotun 3. harmoniğine ve pilot/16 bit saatine kilitli."""
import numpy as np, sys

FS = 256000
# --- RDS kodlama (IEC 62106 Ek B) ---
H = [0b1000000000,0b0100000000,0b0010000000,0b0001000000,0b0000100000,0b0000010000,0b0000001000,
     0b0000000100,0b0000000010,0b0000000001,0b1011011100,0b0101101110,0b0010110111,0b1010000111,
     0b1110011111,0b1100010011,0b1101010101,0b1101110110,0b0110111011,0b1000000001,0b1111011100,
     0b0111101110,0b0011110111,0b1010100111,0b1110001111,0b1100011011]
SYN = {'A':0b1111011000,'B':0b1111010100,'C':0b1001011100,'Cp':0b1111001100,'D':0b1001011000}
def syndrome(w):
    r=0
    for k in range(26):
        if (w>>k)&1: r^=H[25-k]
    return r
def encode_block(info16, off):
    for c in range(1024):
        w=(info16<<10)|c
        if syndrome(w)==SYN[off]: return w
    raise RuntimeError
def group_bits(blocks):
    bits=[]
    for info,off in zip(blocks,['A','B','C','D']):
        w=encode_block(info,off)
        bits += [(w>>(25-i))&1 for i in range(26)]
    return bits

def make_rds_bits(pi=0x4E21, ps="TESTFM  ", rt="Merhaba Dunya", pty=10, repeat=60):
    ps = (ps+"        ")[:8]
    rt_b = rt.encode('latin-1') + b'\x0d'
    rt_b += b' '*((-len(rt_b))%4)
    groups=[]
    for rep in range(repeat):
        for addr in range(4):
            b2 = (0<<12)|(0<<11)|(1<<10)|(pty<<5)|(0<<4)|(0<<3)|(0<<2)|addr
            d = (ord(ps[2*addr])<<8)|ord(ps[2*addr+1])
            groups.append(group_bits([pi,b2,0xE0CD,d]))
        for seg in range(len(rt_b)//4):
            b2 = (2<<12)|(0<<11)|(1<<10)|(pty<<5)|(0<<4)|seg
            c = (rt_b[4*seg]<<8)|rt_b[4*seg+1]; d=(rt_b[4*seg+2]<<8)|rt_b[4*seg+3]
            groups.append(group_bits([pi,b2,c,d]))
    bits=[b for g in groups for b in g]
    return np.array(bits,dtype=np.int8)

def synth(seconds=20, l_hz=1000.0, r_hz=2500.0, l_amp=0.8, r_amp=0.0, rds_amp=0.04,
          rds_phase=0.3, pilot_amp=0.09, stereo=True, noise=0.0, seed=1):
    n=int(seconds*FS); t=np.arange(n)/FS
    L=l_amp*np.sin(2*np.pi*l_hz*t); R=r_amp*np.sin(2*np.pi*r_hz*t)
    p=2*np.pi*19000*t
    if stereo:
        m = 0.9*((L+R)/2 + (L-R)/2*np.sin(2*p)) + pilot_amp*np.sin(p)
    else:
        m = 0.9*(L+R)/2
    bits=make_rds_bits()
    # diferansiyel kodlama
    y=np.zeros(len(bits),dtype=np.int8); prev=0
    for i,x in enumerate(bits):
        prev = x ^ prev; y[i]=prev
    # bifaz: bit başına 2 yonga, saat = 19000/16 bit/s (pilot kilitli)
    chip_idx=np.floor(2*19000*t/16).astype(np.int64)
    bit_idx=(chip_idx//2)%len(y); half=chip_idx%2
    sign=np.where(y[bit_idx]==1, np.where(half==0,1.0,-1.0), np.where(half==0,-1.0,1.0))
    # (bit şekillendirme: tek kutupluk dikdörtgen yonga yeterli; alıcı süzgeci zaten bant sınırlar)
    if stereo:
        m = m + rds_amp*sign*np.cos(3*p+rds_phase)
    rng=np.random.default_rng(seed)
    if noise>0: m = m + noise*rng.standard_normal(n)
    return m.astype(np.float32)

if __name__=="__main__":
    out=sys.argv[1]
    kw={}
    for a in sys.argv[2:]:
        k,v=a.split('=');
        kw[k]= (v=='True') if v in('True','False') else float(v)
    synth(**kw).tofile(out)
