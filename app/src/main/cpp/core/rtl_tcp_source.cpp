#include "rtl_tcp_source.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <chrono>
#include <cerrno>
#include <cstring>

namespace oto {

namespace {
const int kE4k[] = {-10, 15, 40, 65, 90, 115, 140, 165, 190, 215, 240, 290, 340, 420};
const int kFc0012[] = {-99, -40, 71, 179, 192};
const int kFc0013[] = {-99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65, 67, 68, 70, 71, 179, 181, 182, 184, 186, 188, 191, 197};
const int kR82xx[] = {0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496};

std::vector<int> gainTable(int tuner) {
    switch (tuner) {
        case 1: return std::vector<int>(std::begin(kE4k), std::end(kE4k));
        case 2: return std::vector<int>(std::begin(kFc0012), std::end(kFc0012));
        case 3: return std::vector<int>(std::begin(kFc0013), std::end(kFc0013));
        case 4: return {0};
        default: return std::vector<int>(std::begin(kR82xx), std::end(kR82xx));
    }
}

bool readExact(int fd, void* buf, size_t len, int timeoutMs) {
    auto* p = static_cast<uint8_t*>(buf);
    size_t got = 0;
    while (got < len) {
        pollfd pfd{fd, POLLIN, 0};
        if (poll(&pfd, 1, timeoutMs) <= 0) return false;
        const ssize_t r = recv(fd, p + got, len - got, 0);
        if (r <= 0) {
            if (r < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            return false;
        }
        got += static_cast<size_t>(r);
    }
    return true;
}
}  // namespace

RtlTcpSource::RtlTcpSource(SampleFifo& fifo) : fifo_(fifo) {}
RtlTcpSource::~RtlTcpSource() { close(); }

bool RtlTcpSource::open(const std::string& host, int port, int timeoutMs, std::string* error) {
    close();
    host_ = host;
    port_ = port;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    int fd = -1;
    while (std::chrono::steady_clock::now() < deadline) {
        fd = ::socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) break;
        sockaddr_in a{};
        a.sin_family = AF_INET;
        a.sin_port = htons(static_cast<uint16_t>(port));
        if (inet_pton(AF_INET, host.c_str(), &a.sin_addr) != 1) {
            ::close(fd);
            fd = -1;
            break;
        }
        const int fl = fcntl(fd, F_GETFL, 0);
        fcntl(fd, F_SETFL, fl | O_NONBLOCK);
        int rc = ::connect(fd, reinterpret_cast<sockaddr*>(&a), sizeof(a));
        bool ok = rc == 0;
        if (rc < 0 && errno == EINPROGRESS) {
            pollfd pfd{fd, POLLOUT, 0};
            if (poll(&pfd, 1, 500) > 0) {
                int err = 0;
                socklen_t l = sizeof(err);
                getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &l);
                ok = err == 0;
            }
        }
        if (ok) {
            fcntl(fd, F_SETFL, fl);
            break;
        }
        ::close(fd);
        fd = -1;
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
    if (fd < 0) {
        if (error) *error = "@nat_tcp_connect";
        return false;
    }
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    uint8_t hdr[12];
    if (!readExact(fd, hdr, sizeof(hdr), 3000) || std::memcmp(hdr, "RTL0", 4) != 0) {
        ::close(fd);
        if (error) *error = "@nat_tcp_header";
        return false;
    }
    tunerType_ = static_cast<int>((uint32_t(hdr[4]) << 24) | (uint32_t(hdr[5]) << 16) | (uint32_t(hdr[6]) << 8) | hdr[7]);
    fd_ = fd;
    fe_.setGains(gainTable(tunerType_));
    fe_.setSoftwareAgc(true);
    running_ = true;
    send(0x02, 2048000);
    send(0x03, 1);
    applyGain(fe_.resetGainIdx());
    conv_.assign(65536, 0.0f);
    rx_ = std::thread(&RtlTcpSource::rxLoop, this);
    agc_ = std::thread(&RtlTcpSource::agcLoop, this);
    return true;
}

void RtlTcpSource::close() {
    running_ = false;
    if (fd_ >= 0) ::shutdown(fd_, SHUT_RDWR);
    if (rx_.joinable()) rx_.join();
    if (agc_.joinable()) agc_.join();
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
}

bool RtlTcpSource::send(uint8_t cmd, uint32_t param) {
    std::lock_guard<std::mutex> lk(sendMutex_);
    if (fd_ < 0) return false;
    uint8_t m[5] = {cmd, uint8_t(param >> 24), uint8_t(param >> 16), uint8_t(param >> 8), uint8_t(param)};
    return ::send(fd_, m, sizeof(m), MSG_NOSIGNAL) == static_cast<ssize_t>(sizeof(m));
}

void RtlTcpSource::applyGain(int idx) {
    idx = fe_.clampIdx(idx);
    fe_.storeGainIdx(idx);
    send(0x04, static_cast<uint32_t>(fe_.gainTenthDb(idx)));
}

void RtlTcpSource::setGainIndex(int index) {
    fe_.setSoftwareAgc(false);
    applyGain(index);
}

void RtlTcpSource::tuneHz(uint32_t hz) {
    freq_ = hz;
    send(0x01, hz);
    if (fe_.softwareAgc()) applyGain(fe_.resetGainIdx());
    fe_.resetAgcLevel();
    discardBytes_ = 2 * 262144;  // ~128 ms bayat/kararsız veri
    pendingTuned_ = true;
}

void RtlTcpSource::rxLoop() {
    std::vector<uint8_t> buf(65536);
    while (running_) {
        const ssize_t r = ::recv(fd_, buf.data(), buf.size(), 0);
        if (r < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (r <= 0) break;
        size_t off = 0, len = static_cast<size_t>(r);
        int64_t d = discardBytes_.load();
        if (d > 0) {
            const size_t drop = static_cast<size_t>(std::min<int64_t>(d, static_cast<int64_t>(len)));
            discardBytes_ = d - static_cast<int64_t>(drop);
            off = drop;
            len -= drop;
            if (discardBytes_ == 0 && pendingTuned_.exchange(false)) {
                fifo_.reset();
                fe_.resetDoc();
                if (onTuned) onTuned(freq_.load());
            }
        }
        if (len == 0) continue;
        len &= ~static_cast<size_t>(1);
        if (conv_.size() < len) conv_.resize(len);
        fe_.convert(buf.data() + off, len, conv_.data());
        fifo_.write(conv_.data(), len);
    }
    if (running_.exchange(false) && onError) onError("@nat_tcp_lost");
}

void RtlTcpSource::agcLoop() {
    while (running_) {
        std::this_thread::sleep_for(std::chrono::milliseconds(256));
        if (discardBytes_ > 0) continue;
        const int idx = fe_.agcDecision();
        if (idx >= 0 && fe_.softwareAgc()) applyGain(idx);
    }
}

}  // namespace oto
