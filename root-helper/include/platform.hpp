#pragma once
#include "helper.hpp"
#include <array>
#include <functional>

namespace sayin {
class Crypto {
public:
    Crypto();
    ~Crypto();
    Crypto(const Crypto&) = delete;
    Crypto& operator=(const Crypto&) = delete;
    std::vector<uint8_t> random(size_t size) const;
    std::vector<uint8_t> seal(std::span<const uint8_t> key, std::span<const uint8_t> plain) const;
    std::vector<uint8_t> open(std::span<const uint8_t> key, std::span<const uint8_t> encrypted) const;
    void self_test() const;
private:
    void* lib_;
};
class Store {
public:
    explicit Store(const std::string& path);
    ~Store();
    Store(const Store&) = delete;
    Store& operator=(const Store&) = delete;
    History load();
    void save(const History& history);
    void initialize();
    bool exists(const char* name) const;
    void flag(bool enable);
    bool enabled() const;
    void write_state(const std::string& state) const;
    int directory() const { return fd_; }
private:
    int fd_ = -1;
    Crypto crypto_;
    std::vector<uint8_t> key_;
    std::vector<uint8_t> read(const char* name, size_t limit) const;
    void replace(const char* name, std::span<const uint8_t> data) const;
    void get_key();
};
// The child drops to shell before opening Binder. Parent alone owns private history.
int collector(int channel, const std::string& test_prefix);
int probe(bool validate_clip = false);
struct Packet {
    uint32_t kind = 0; // 1=text, 2=ready/paused, 3=drop, 4=fatal
    uint32_t state = 0;
    int64_t timestamp = 0;
    uint32_t size = 0;
    char text[kMaxText]{};
};
}
