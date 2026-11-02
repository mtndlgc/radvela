// Çekirdekten uygulama katmanına (JNI) giden olaylar.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace oto {

class EngineListener {
public:
    virtual ~EngineListener() = default;

    // PCM: int16 aralıklı, her zaman stereo (L R L R ...)
    virtual void onAudio(const int16_t* /*pcm*/, size_t /*frames*/, int /*sampleRate*/) {}

    // --- FM ---
    virtual void onFmStatus(float /*rssiRelDb*/, bool /*stereo*/, bool /*rdsSync*/) {}
    virtual void onFmPi(uint16_t /*pi*/) {}
    virtual void onFmPs(const std::string& /*ps*/) {}
    virtual void onFmRt(const std::string& /*rt*/) {}
    virtual void onFmPty(int /*pty*/) {}
    virtual void onFmStationFound(int /*freqHz*/, float /*strengthDb*/) {}
    virtual void onFmStationName(int /*freqHz*/, const std::string& /*ps*/) {}

    // --- DAB/DAB+ ---
    virtual void onDabEnsemble(const std::string& /*channel*/, const std::string& /*label*/, uint32_t /*eid*/) {}
    // sid: ECC birleşik hizmet kimliği (dabsdr isteklerinde kullanılan değer)
    virtual void onDabService(const std::string& /*channel*/, uint32_t /*sid*/, int /*scids*/, const std::string& /*label*/,
                              bool /*isAac*/) {}
    virtual void onDabServicesComplete(const std::string& /*channel*/) {}
    virtual void onDabSelected(uint32_t /*sid*/, int /*scids*/, const std::string& /*label*/) {}
    virtual void onDabStatus(float /*snrDb*/, int /*syncLevel*/) {}  // 0 yok, 1 null, 2 tam
    // Periyodik kalite sayaçları (son bildirim aralığı): FIC hatası, ses çerçevesi CRC tamam/hatalı, RS düzeltilemeyen
    virtual void onDabQuality(int /*fibErr*/, int /*mscOk*/, int /*mscErr*/, int /*rsErr*/) {}
    virtual void onDabLabel(const std::string& /*dls*/) {}
    virtual void onDabAudioParams(const std::string& /*coding*/, int /*rateHz*/, bool /*stereo*/) {}
    // MOT slayt gösterisi: ham görüntü baytları; subtype 1 = JPEG, 3 = PNG
    virtual void onDabSlide(const uint8_t* /*data*/, size_t /*len*/, int /*subtype*/) {}

    // --- Genel ---
    virtual void onScanProgress(int /*percent*/, const std::string& /*what*/) {}
    virtual void onScanFreq(int /*freqHz*/) {}  // FM taramasında o an bakılan frekans
    virtual void onScanDone(bool /*cancelled*/) {}
    virtual void onConnection(bool /*connected*/, const std::string& /*description*/) {}
    virtual void onGain(float /*db*/, bool /*auto*/) {}
    virtual void onMessage(bool /*isError*/, const std::string& /*text*/) {}
};

}  // namespace oto
