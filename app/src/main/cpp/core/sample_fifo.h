// IQ örnekleri için kilitli halka tampon (float, I/Q aralıklı, ölçek: ±128).
// Üretici: USB/rtl_tcp okuyucu iş parçacığı. Tüketici: dabsdr giriş işlevi veya FM alıcısı.
#pragma once

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

namespace oto {

class SampleFifo {
public:
    explicit SampleFifo(size_t capacityFloats = 4u * 1024u * 1024u) : buf_(capacityFloats) {}

    size_t capacity() const { return buf_.size(); }

    // Yer yoksa fazlasını atar; yazılan float sayısını döndürür.
    size_t write(const float* data, size_t n) {
        std::lock_guard<std::mutex> lk(m_);
        const size_t room = buf_.size() - count_;
        const size_t w = std::min(n, room);
        if (w < n) dropped_ += n - w;
        size_t first = std::min(w, buf_.size() - head_);
        std::memcpy(&buf_[head_], data, first * sizeof(float));
        if (w > first) std::memcpy(&buf_[0], data + first, (w - first) * sizeof(float));
        head_ = (head_ + w) % buf_.size();
        count_ += w;
        cv_.notify_all();
        return w;
    }

    // n float birikene kadar bekler (flush edilirse false + sıfır doldurur).
    bool readBlocking(float* out, size_t n) {
        std::unique_lock<std::mutex> lk(m_);
        cv_.wait(lk, [&] { return count_ >= n || flush_; });
        if (flush_) {
            std::memset(out, 0, n * sizeof(float));
            return false;
        }
        take(out, n);
        return true;
    }

    // Çıktıyı atlayarak n float tüketir.
    bool skipBlocking(size_t n) {
        std::unique_lock<std::mutex> lk(m_);
        cv_.wait(lk, [&] { return count_ >= n || flush_; });
        if (flush_) return false;
        tail_ = (tail_ + n) % buf_.size();
        count_ -= n;
        return true;
    }

    // En az n float birikmesini bekler (zaman aşımı ms).
    bool waitAvailable(size_t n, int timeoutMs) {
        std::unique_lock<std::mutex> lk(m_);
        cv_.wait_for(lk, std::chrono::milliseconds(timeoutMs), [&] { return count_ >= n || flush_; });
        return count_ >= n && !flush_;
    }

    // Engellemeden, en fazla maxN float okur.
    size_t readSome(float* out, size_t maxN) {
        std::lock_guard<std::mutex> lk(m_);
        const size_t r = std::min(maxN, count_);
        take(out, r);
        return r;
    }

    size_t available() const {
        std::lock_guard<std::mutex> lk(m_);
        return count_;
    }

    // Veriyi temizler ve flush bayrağını kaldırır.
    void reset() {
        std::lock_guard<std::mutex> lk(m_);
        head_ = tail_ = count_ = 0;
        flush_ = false;
        cv_.notify_all();
    }

    // Bekleyen okuyucuları uyandırır (readBlocking false döner). reset() ile kaldırılır.
    void flush() {
        std::lock_guard<std::mutex> lk(m_);
        flush_ = true;
        cv_.notify_all();
    }

    uint64_t dropped() const {
        std::lock_guard<std::mutex> lk(m_);
        return dropped_;
    }

private:
    void take(float* out, size_t n) {  // m_ kilitliyken
        size_t first = std::min(n, buf_.size() - tail_);
        std::memcpy(out, &buf_[tail_], first * sizeof(float));
        if (n > first) std::memcpy(out + first, &buf_[0], (n - first) * sizeof(float));
        tail_ = (tail_ + n) % buf_.size();
        count_ -= n;
    }

    mutable std::mutex m_;
    std::condition_variable cv_;
    std::vector<float> buf_;
    size_t head_ = 0, tail_ = 0, count_ = 0;
    bool flush_ = false;
    uint64_t dropped_ = 0;
};

// dabsdr giriş işlevi bağlam taşımadığından tek bir genel tampon kullanılır.
inline SampleFifo& inputFifo() {
    static SampleFifo fifo(4u * 1024u * 1024u);  // ~0,5 sn @ 2,048 MS/s
    return fifo;
}

}  // namespace oto
