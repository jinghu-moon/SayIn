#include "helper.hpp"
#include <algorithm>
#include <limits>

namespace sayin {
void append_u32(std::vector<uint8_t>& out, uint32_t value) {
    for (int i = 0; i < 4; ++i) out.push_back(static_cast<uint8_t>(value >> (8 * i)));
}
void append_u64(std::vector<uint8_t>& out, uint64_t value) {
    for (int i = 0; i < 8; ++i) out.push_back(static_cast<uint8_t>(value >> (8 * i)));
}
bool History::add(const Clip& clip) {
    if (clip.sensitive || !valid_text(clip.text) || clip.timestamp <= last_timestamp) return false;
    if (next_id == UINT64_MAX) throw Failure("id_exhausted");
    last_timestamp = clip.timestamp;
    std::erase_if(entries_, [&](const Entry& e) { return e.text == clip.text; });
    entries_.insert(entries_.begin(), Entry{next_id++, clip.timestamp, clip.text});
    size_t bytes = 0, keep = 0;
    for (const auto& entry : entries_) {
        if (keep == kMaxEntries || entry.text.size() > kMaxBytes - bytes) break;
        bytes += entry.text.size(); ++keep;
    }
    entries_.resize(keep); return true;
}
void History::clear() {
    if (generation == UINT64_MAX) throw Failure("generation_exhausted");
    ++generation; entries_.clear(); // Keep the source timestamp barrier across restart.
}
std::vector<uint8_t> History::encode() const {
    std::vector<uint8_t> bytes{'S','I','H','1'};
    append_u64(bytes, generation); append_u64(bytes, next_id);
    append_u64(bytes, static_cast<uint64_t>(last_timestamp));
    append_u32(bytes, static_cast<uint32_t>(entries_.size()));
    for (const auto& entry : entries_) {
        append_u64(bytes, entry.id); append_u64(bytes, static_cast<uint64_t>(entry.timestamp));
        append_u32(bytes, static_cast<uint32_t>(entry.text.size()));
        bytes.insert(bytes.end(), entry.text.begin(), entry.text.end());
    }
    return bytes;
}
History History::decode(std::span<const uint8_t> bytes) {
    if (bytes.size() < 32 || bytes.size() > kMaxBytes + 2048 || std::string(bytes.begin(), bytes.begin()+4) != "SIH1") throw Failure("history_header");
    size_t pos = 4;
    auto read = [&](size_t count) {
        if (count > bytes.size() - pos) throw Failure("history_truncated");
        uint64_t value = 0; for (size_t i = 0; i < count; ++i) value |= uint64_t(bytes[pos++]) << (8 * i);
        return value;
    };
    History h; h.generation = read(8); h.next_id = read(8); h.last_timestamp = static_cast<int64_t>(read(8));
    auto count = read(4); if (!h.generation || !h.next_id || h.last_timestamp < 0 || count > kMaxEntries) throw Failure("history_metadata");
    size_t total = 0; uint64_t previous = h.next_id;
    for (size_t i = 0; i < count; ++i) {
        const auto id = read(8); const auto timestamp = static_cast<int64_t>(read(8)); const auto length = read(4);
        if (length > kMaxText || length > bytes.size() - pos || length > kMaxBytes - total || id == 0 || id >= previous || timestamp <= 0 || timestamp > h.last_timestamp) throw Failure("history_entry");
        std::string text(reinterpret_cast<const char*>(bytes.data() + pos), length); pos += length; total += length; previous = id;
        if (!valid_text(text) || std::any_of(h.entries_.begin(), h.entries_.end(), [&](const Entry& e) { return e.text == text; })) throw Failure("history_text");
        h.entries_.push_back({id, timestamp, std::move(text)});
    }
    if (pos != bytes.size()) throw Failure("history_trailing");
    return h;
}
std::string History::json() const {
    std::string out = "{\"generation\":" + std::to_string(generation) + ",\"entries\":[";
    bool first = true;
    for (const auto& e : entries_) {
        if (!first) out += ',';
        first = false;
        out += "{\"id\":" + std::to_string(e.id) + ",\"timestamp\":" + std::to_string(e.timestamp) + ",\"text\":" + json_string(e.text) + "}";
    }
    return out + "]}";
}
}
