#include "helper.hpp"
#include <iostream>
#include <functional>

using namespace sayin;
static int checks = 0;
void require(bool condition) { ++checks; if (!condition) throw Failure("test_assertion_" + std::to_string(checks)); }
void rejects(const std::function<void()>& function) { bool failed = false; try { function(); } catch (const Failure&) { failed = true; } require(failed); }
void i32(std::vector<uint8_t>& b, int value) { append_u32(b, static_cast<uint32_t>(value)); }
void s8(std::vector<uint8_t>& b, const std::string& text) {
    i32(b, static_cast<int>(text.size())); b.insert(b.end(), text.begin(), text.end()); b.push_back(0);
    while (b.size() % 4) b.push_back(0);
}
void s16(std::vector<uint8_t>& b, const std::string& text) {
    i32(b, static_cast<int>(text.size()));
    for (const auto c : text) { b.push_back(static_cast<uint8_t>(c)); b.push_back(0); }
    b.push_back(0); b.push_back(0); while (b.size() % 4) b.push_back(0);
}
std::vector<uint8_t> sample(bool sensitive = false, std::string text = "hello") {
    std::vector<uint8_t> b;
    i32(b, 1); i32(b, 1); s8(b, "fixture"); // present, plain label
    i32(b, 1); s16(b, "text/plain");
    if (sensitive) {
        std::vector<uint8_t> extras; i32(extras, 1); s16(extras, "android.content.extra.IS_SENSITIVE"); i32(extras, 9); i32(extras, 1);
        i32(b, static_cast<int>(extras.size())); i32(b, 0x4c444e42); b.insert(b.end(), extras.begin(), extras.end());
    } else i32(b, -1);
    append_u64(b, 12345); i32(b, 0); i32(b, 1); i32(b, 0); // timestamp, styled, classification, confidences
    i32(b, 0); i32(b, 1); i32(b, 1); s8(b, text); i32(b, -1);
    for (int i = 0; i < 4; ++i) i32(b, 0);
    return b;
}
int main() {
    try {
        require(valid_text("hello\nworld"));
        require(valid_text("\xe4\xb8\xad\xf0\x9f\x99\x82"));
        for (const auto& value : {std::string(), std::string("\xc0\x80", 2), std::string("\xed\xa0\x80",3), std::string("\xf4\x90\x80\x80",4), std::string("a\0b",3), std::string(kMaxText+1, 'x')}) require(!valid_text(value));
        auto bytes = sample(); const auto clip = parse_clip33(bytes);
        require(clip.text == "hello" && clip.timestamp == 12345 && !clip.sensitive);
        require(parse_description33(bytes) == 12345);
        auto secret = parse_clip33(sample(true, "secret")); require(secret.sensitive && secret.text.empty());
        for (size_t i = 0; i < bytes.size(); ++i) rejects([&] { (void)parse_clip33(std::span(bytes).first(i)); });
        auto trailing = bytes; trailing.push_back(1); rejects([&] { (void)parse_clip33(trailing); });
        auto large = sample(false, std::string(kMaxText+1, 'x')); rejects([&] { (void)parse_clip33(large); });
        for (size_t i = 0; i < bytes.size(); ++i) { auto changed = bytes; changed[i] ^= 0xff; try { auto value = parse_clip33(changed); require(value.sensitive || valid_text(value.text)); } catch (const Failure&) {} }
        History h; require(h.add(clip)); require(!h.add(clip)); require(!h.add(secret));
        require(h.add({12346, "next", false})); require(h.add({12347,"hello",false})); require(h.entries().size()==2 && h.entries()[0].text == "hello");
        for (int i = 0; i < 70; ++i) require(h.add({12400+i, "line_"+std::to_string(i), false}));
        require(h.entries().size() == kMaxEntries);
        auto encoded = h.encode(); auto restored = History::decode(encoded);
        require(restored.encode() == encoded);
        for (size_t i = 0; i < encoded.size(); ++i) rejects([&] { (void)History::decode(std::span(encoded).first(i)); });
        h.clear(); require(h.entries().empty()); require(!h.add({12401,"late",false}));
        require(History::decode(h.encode()).entries().empty());
        History bounded;
        for (int i = 1; i <= 50; ++i) require(bounded.add({i,std::string(kMaxText-3,'x')+std::to_string(i),false}));
        size_t sum = 0; for (const auto& e : bounded.entries()) sum += e.text.size(); require(sum <= kMaxBytes);
        require(json_string("a\n\"\\") == "\"a\\u000a\\\"\\\\\"");
        std::cout << checks << " assertions passed\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
