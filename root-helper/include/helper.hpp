#pragma once

#include <cstdint>
#include <span>
#include <stdexcept>
#include <string>
#include <vector>

namespace sayin {
constexpr size_t kMaxText = 16 * 1024;
constexpr size_t kMaxBytes = 512 * 1024;
constexpr size_t kMaxEntries = 50;
constexpr size_t kMaxParcel = 256 * 1024;
struct Failure : std::runtime_error { using std::runtime_error::runtime_error; };
struct Clip {
    int64_t timestamp = 0;
    std::string text;
    bool sensitive = false;
};
bool valid_text(const std::string& text);
struct PlatformProfile {
    int api;
    uint32_t current_user_transaction;
    bool clipboard_attribution;
    int item_objects;
    bool bundle_intent_marker = false;
    uint32_t interactive_transaction;
};
PlatformProfile platform_profile(int api);
bool capture_allowed(bool ce_available, int current_user, int device_locked, int keyguard_locked, int interactive);
Clip parse_clip(std::span<const uint8_t> bytes, const PlatformProfile& profile);
int64_t parse_description(std::span<const uint8_t> bytes, const PlatformProfile& profile);
std::string json_string(const std::string& text);
struct Entry { uint64_t id; int64_t timestamp; std::string text; };
class History {
public:
    uint64_t next_id = 1;
    uint64_t generation = 1;
    int64_t last_timestamp = 0;
    bool add(const Clip& clip);
    void clear();
    const std::vector<Entry>& entries() const { return entries_; }
    std::vector<uint8_t> encode() const;
    static History decode(std::span<const uint8_t> bytes);
    std::string json() const;
private:
    std::vector<Entry> entries_;
};
void append_u32(std::vector<uint8_t>& out, uint32_t value);
void append_u64(std::vector<uint8_t>& out, uint64_t value);
}
