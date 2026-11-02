#!/usr/bin/env python3
"""FM kompozit (MPX) -> rtl_tcp biçiminde 8-bit IQ (2.048 MS/s). Çıktı baytları I,Q,I,Q...
Donanım LO'su istasyonun +256 kHz üzerinde varsayılır (istasyon -256 kHz'de görünür)."""
import numpy as np, sys
from scipy.signal import resample_poly
FS_IQ=2048000; FS_MPX=256000
def fm_iq(mpx, snr_db=None, amp=0.6, extra=None, seed=3):
    rng=np.random.default_rng(seed)
    up = resample_poly(mpx.astype(np.float64), 8, 1)
    n=len(up)
    phi = 2*np.pi*75000*np.cumsum(up)/FS_IQ
    t = np.arange(n)
    sig = amp*np.exp(1j*(phi - 2*np.pi*FS_IQ/8*t/FS_IQ))
    if extra:
        for (off_hz, a, mpx2) in extra:
            up2 = resample_poly(mpx2.astype(np.float64), 8, 1)[:n]
            phi2 = 2*np.pi*75000*np.cumsum(up2)/FS_IQ
            sig = sig + a*np.exp(1j*(phi2 + 2*np.pi*off_hz*t/FS_IQ))
    if snr_db is not None:
        # SNR, 200 kHz kanal bandında; toplam örnekleme bandına ölçekle
        p = amp**2
        npow = p/(10**(snr_db/10)) * (FS_IQ/200000.0)
        sig = sig + np.sqrt(npow/2)*(rng.standard_normal(n)+1j*rng.standard_normal(n))
    return sig
def quantize(sig):
    out=np.empty(2*len(sig),dtype=np.uint8)
    out[0::2]=np.clip(np.round(127.5+127.5*sig.real),0,255)
    out[1::2]=np.clip(np.round(127.5+127.5*sig.imag),0,255)
    return out
if __name__=="__main__":
    mpx=np.fromfile(sys.argv[1],dtype=np.float32)
    snr = float(sys.argv[3]) if len(sys.argv)>3 else None
    quantize(fm_iq(mpx,snr)).tofile(sys.argv[2])
