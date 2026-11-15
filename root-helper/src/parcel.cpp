#include "helper.hpp"
#include <algorithm>
#include <limits>
#include <optional>

namespace sayin {
namespace {
class Reader {
public:
    explicit Reader(std::span<const uint8_t> source, bool bundle_marker) : data(source), bundle_intent_marker(bundle_marker) {
        if (source.size() > kMaxParcel) throw Failure("parcel_limit");
    }
    std::span<const uint8_t> data;
    size_t pos = 0;
    bool bundle_intent_marker;
    void need(size_t size) const { if (size > data.size() - pos) throw Failure("parcel_truncated"); }
    int32_t i32() {
        need(4); uint32_t value = 0;
        for (int i = 0; i < 4; ++i) value |= uint32_t(data[pos++]) << (8 * i);
        return static_cast<int32_t>(value);
    }
    int64_t i64() {
        const auto low = static_cast<uint32_t>(i32());
        return static_cast<int64_t>(uint64_t(low) | (uint64_t(static_cast<uint32_t>(i32())) << 32));
    }
    std::optional<std::string> string8() {
        const int32_t size = i32();
        if (size == -1) return {};
        if (size < 0 || size > static_cast<int32_t>(kMaxText)) throw Failure("string_limit");
        const size_t aligned = (static_cast<size_t>(size) + 4) & ~size_t(3);
        need(aligned);
        if (data[pos + size] != 0) throw Failure("string_terminator");
        std::string value(reinterpret_cast<const char*>(data.data() + pos), size);
        pos += aligned; return value;
    }
    std::optional<std::string> string16() {
        const int32_t size = i32();
        if (size == -1) return {};
        if (size < 0 || size > 1024) throw Failure("metadata_limit");
        const size_t aligned = (static_cast<size_t>(size) * 2 + 5) & ~size_t(3);
        need(aligned); std::string value;
        // Metadata keys and MIME types used here are ASCII. Reject unknown encodings.
        for (int i = 0; i < size; ++i) {
            const auto a = data[pos + 2 * i], b = data[pos + 2 * i + 1];
            if (b || a == 0 || a > 127) throw Failure("metadata_encoding");
            value.push_back(static_cast<char>(a));
        }
        if (data[pos + 2 * size] || data[pos + 2 * size + 1]) throw Failure("metadata_terminator");
        pos += aligned; return value;
    }
    std::optional<std::string> plain() {
        if (i32() != 1) throw Failure("styled_text_unsupported");
        return string8();
    }
    bool extras() {
        const int32_t length = i32();
        if (length == -1 || length == 0) return false;
        if (length < 0 || length > 32 * 1024 || length % 4 != 0) throw Failure("extras_length");
        if (i32() != 0x4c444e42) throw Failure("extras_magic");
        need(length); const size_t end = pos + length;
        const int count = i32();
        if (count < 0 || count > 32) throw Failure("extras_count");
        bool sensitive = false;
        for (int i = 0; i < count; ++i) {
            auto key = string16(); if (!key) throw Failure("extras_key");
            const int type = i32();
            // Parse only bounded primitive PersistableBundle values; unknown => reject clip.
            if (type == 9) {
                const int value = i32(); if (value != 0 && value != 1) throw Failure("extras_boolean");
                if (*key == "android.content.extra.IS_SENSITIVE") sensitive |= value != 0;
            } else {
                if (*key == "android.content.extra.IS_SENSITIVE") throw Failure("sensitivity_type");
                switch (type) {
                    case -1: break;
                    case 0: (void)string16(); break;
                    case 1: (void)i32(); break;
                    case 6: case 8: (void)i64(); break;
                    default: throw Failure("extras_type_unsupported");
                }
            }
            if (pos > end) throw Failure("extras_overrun");
        }
        if (pos != end) throw Failure("extras_trailing");
        if (bundle_intent_marker && i32() != 0) throw Failure("bundle_intent_unsupported");
        return sensitive;
    }
    void skip_bundle() {
        const int length = i32();
        if (length == -1 || length == 0) return;
        if (length < 0 || length > 32 * 1024 || length % 4) throw Failure("bundle_length");
        if (i32() != 0x4c444e42) throw Failure("bundle_magic");
        need(length); pos += length;
        if (bundle_intent_marker && i32() != 0) throw Failure("bundle_intent_unsupported");
    }
    Clip description() {
        (void)plain(); // Label is never stored or logged.
        const int count = i32();
        if (count < 1 || count > 8) throw Failure("mime_count");
        bool text = false;
        for (int i = 0; i < count; ++i) {
            const auto mime = string16();
            if (mime == "text/plain") text = true;
            else throw Failure("non_plain_mime");
        }
        if (!text) throw Failure("non_text");
        Clip clip;
        clip.sensitive = extras(); clip.timestamp = i64();
        if (i32() != 0) throw Failure("styled_description");
        const int classification = i32();
        if (classification < 1 || classification > 3) throw Failure("classification_layout");
        skip_bundle();
        if (clip.timestamp <= 0) throw Failure("invalid_timestamp");
        return clip;
    }
};
}

bool valid_text(const std::string& text) {
    if (text.empty() || text.size() > kMaxText) return false;
    size_t i = 0;
    while (i < text.size()) {
        const auto a = static_cast<uint8_t>(text[i++]);
        if (a < 0x80) { if (a == 0 || (a < 0x20 && a != '\n' && a != '\r' && a != '\t')) return false; continue; }
        int extra; uint32_t point, minimum;
        if ((a & 0xe0) == 0xc0) { extra = 1; point = a & 0x1f; minimum = 0x80; }
        else if ((a & 0xf0) == 0xe0) { extra = 2; point = a & 0xf; minimum = 0x800; }
        else if ((a & 0xf8) == 0xf0) { extra = 3; point = a & 7; minimum = 0x10000; }
        else return false;
        if (static_cast<size_t>(extra) > text.size() - i) return false;
        while (extra--) { const auto c = static_cast<uint8_t>(text[i++]); if ((c & 0xc0) != 0x80) return false; point = (point << 6) | (c & 0x3f); }
        if (point < minimum || point > 0x10ffff || (point >= 0xd800 && point <= 0xdfff)) return false;
    }
    return true;
}

PlatformProfile platform_profile(int api) {
    switch (api) {
        case 33: return {33, 126, false, 4, false, 16};
        case 37: return {37, 142, true, 5, false, 23};
        default: throw Failure("unsupported_platform_api");
    }
}

bool capture_allowed(bool ce_available, int current_user, int device_locked, int keyguard_locked, int interactive) {
    return ce_available && current_user == 0 && device_locked == 0 && keyguard_locked == 0 && interactive == 1;
}

Clip parse_clip(std::span<const uint8_t> bytes, const PlatformProfile& profile) {
    Reader reader(bytes, profile.bundle_intent_marker);
    if (reader.i32() != 1) throw Failure("no_clip");
    Clip clip = reader.description();
    if (clip.sensitive) return clip; // Never extract sensitive body.
    if (reader.i32() != 0) throw Failure("icon_unsupported");
    if (reader.i32() != 1) throw Failure("multiple_items");
    auto text = reader.plain();
    if (!text || !valid_text(*text)) throw Failure("invalid_text");
    clip.text = std::move(*text);
    if (reader.string8()) throw Failure("html_unsupported");
    // API 37 adds IntentSender. Never materialize any of these complex objects.
    for (int i = 0; i < profile.item_objects; ++i) if (reader.i32() != 0) throw Failure("complex_item_unsupported");
    if (reader.pos != bytes.size()) throw Failure("parcel_trailing");
    return clip;
}

int64_t parse_description(std::span<const uint8_t> bytes, const PlatformProfile& profile) {
    Reader reader(bytes, profile.bundle_intent_marker);
    if (reader.i32() != 1) return 0;
    return reader.description().timestamp;
}

std::string json_string(const std::string& text) {
    std::string result = "\""; constexpr char hex[] = "0123456789abcdef";
    for (const unsigned char c : text) {
        if (c == '"' || c == '\\') { result += '\\'; result += static_cast<char>(c); }
        else if (c < 32) { result += "\\u00"; result += hex[c >> 4]; result += hex[c & 15]; }
        else result += static_cast<char>(c);
    }
    return result + "\"";
}
}
