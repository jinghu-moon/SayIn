#include "helper.hpp"
#include "app_bridge.hpp"
#include <iostream>
#include <functional>
#include <fstream>

using namespace sayin;
static int checks = 0;
const auto profile33 = platform_profile(33);
const auto profile37 = platform_profile(37);
Clip parse_clip33(std::span<const uint8_t> bytes) { return parse_clip(bytes, profile33); }
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
std::vector<uint8_t> sample(bool sensitive = false, std::string text = "hello", bool spanned = false, bool classified = false) {
    std::vector<uint8_t> b;
    i32(b, 1); i32(b, 1); s8(b, "fixture"); // present, plain label
    i32(b, 1); s16(b, "text/plain");
    if (sensitive) {
        std::vector<uint8_t> extras; i32(extras, 1); s16(extras, "android.content.extra.IS_SENSITIVE"); i32(extras, 9); i32(extras, 1);
        i32(b, static_cast<int>(extras.size())); i32(b, 0x4c444e42); b.insert(b.end(), extras.begin(), extras.end());
    } else i32(b, -1);
    append_u64(b, 12345); i32(b, 0); i32(b, 1); i32(b, 0); // timestamp, styled, classification, confidences
    i32(b, 0); i32(b, 1); i32(b, spanned ? 0 : 1); s8(b, text);
    if (spanned) i32(b, 0); // SpannedString with no actual ParcelableSpan.
    i32(b, -1);
    for (int i = 0; i < 3; ++i) i32(b, 0);
    i32(b, classified ? 1 : 0);
    if (classified) {
        s16(b, text); i32(b, 1); i32(b, 1); // TextLinks full text, typed list, item present.
        i32(b, 1); s16(b, "url"); i32(b, 0x3f800000); // EntityConfidence.
        i32(b, 0); i32(b, 5); i32(b, 0); i32(b, 0); // Range, link extras, links extras.
    }
    return b;
}
int main(int argc, char** argv) {
    try {
        require(valid_text("hello\nworld"));
        require(valid_text("\xe4\xb8\xad\xf0\x9f\x99\x82"));
        for (const auto& value : {std::string(), std::string("\xc0\x80", 2), std::string("\xed\xa0\x80",3), std::string("\xf4\x90\x80\x80",4), std::string("a\0b",3), std::string(kMaxText+1, 'x')}) require(!valid_text(value));
        auto bytes = sample(); const auto clip = parse_clip33(bytes);
        require(clip.text == "hello" && clip.timestamp == 12345 && !clip.sensitive);
        require(parse_description(bytes, profile33) == 12345);
        auto secret = parse_clip33(sample(true, "secret")); require(secret.sensitive && secret.text.empty());
        require(parse_clip33(sample(false, "hello", true)).text == "hello");
        const auto classified = sample(false, "hello", true, true);
        require(parse_clip33(classified).text == "hello");
        for (size_t i = 0; i < classified.size(); ++i) rejects([&] { (void)parse_clip33(std::span(classified).first(i)); });
        const size_t links = sample(false, "hello", true).size() - 4;
        for (const auto& change : std::vector<std::pair<size_t, uint8_t>>{{links,2}, {links+4,255}, {links+18,1}, {links+24,255}, {links+28,0}, {links+32,0}}) {
            auto invalid = classified; invalid[change.first] = change.second;
            rejects([&] { (void)parse_clip33(invalid); });
        }
        auto modern_classified = classified;
        modern_classified.insert(modern_classified.begin()+static_cast<std::ptrdiff_t>(links), 4, 0);
        require(parse_clip(modern_classified, profile37).text == "hello");
        for (size_t i = 0; i < bytes.size(); ++i) rejects([&] { (void)parse_clip33(std::span(bytes).first(i)); });
        auto trailing = bytes; trailing.push_back(1); rejects([&] { (void)parse_clip33(trailing); });
        auto large = sample(false, std::string(kMaxText+1, 'x')); rejects([&] { (void)parse_clip33(large); });
        for (size_t i = 0; i < bytes.size(); ++i) { auto changed = bytes; changed[i] ^= 0xff; try { auto value = parse_clip33(changed); require(value.sensitive || valid_text(value.text)); } catch (const Failure&) {} }
        require(profile33.current_user_transaction == 126 && !profile33.clipboard_attribution && profile33.item_objects == 4);
        require(profile37.current_user_transaction == 142 && profile37.clipboard_attribution && profile37.item_objects == 5);
        require(profile33.interactive_transaction == 16 && profile37.interactive_transaction == 23);
        require(capture_allowed(true, 0, 0, 0, 1));
        require(!capture_allowed(true, 0, 0, 1, 0)); // Reproduced: swipe keyguard + screen off, deviceLocked false.
        require(!capture_allowed(true, 0, 0, 1, 1)); // Screen lit but keyguard still showing.
        require(!capture_allowed(true, 0, 0, 0, 0)); // Screen off with no configured keyguard.
        require(!capture_allowed(true, 0, 1, 0, 1));
        require(!capture_allowed(false, 0, 0, 0, 1));
        require(!capture_allowed(true, 10, 0, 0, 1));
        for (int unknown : {-1, 2, 10}) {
            require(!capture_allowed(true, 0, unknown, 0, 1));
            require(!capture_allowed(true, 0, 0, unknown, 1));
            require(!capture_allowed(true, 0, 0, 0, unknown));
        }
        for (int api : {0, 29, 32, 34, 35, 36, 38}) rejects([&] { (void)platform_profile(api); });
        auto modern = bytes; i32(modern, 0);
        require(parse_clip(modern, profile37).text == "hello");
        rejects([&] { (void)parse_clip(modern, profile33); });
        rejects([&] { (void)parse_clip(bytes, profile37); });
        modern.back() = 1; rejects([&] { (void)parse_clip(modern, profile37); });
        require(argc == 2);
        for (bool sensitive : {false, true}) {
            std::ifstream input(std::string(argv[1]) + (sensitive ? "/clip-api37-sensitive.hex" : "/clip-api37-plain.hex"));
            require(input.good()); std::vector<uint8_t> captured;
            unsigned int value;
            while (input >> std::hex >> value) { require(value <= 255); captured.push_back(static_cast<uint8_t>(value)); }
            require(!captured.empty());
            auto vendor37 = profile37; vendor37.bundle_intent_marker = true;
            const auto actual = parse_clip(captured, vendor37);
            require(actual.timestamp == 12345 && actual.sensitive == sensitive);
            require(sensitive ? actual.text.empty() : actual.text == "SAYIN_TEST_platform_\xe4\xb8\xad\xe6\x96\x87\xf0\x9f\x99\x82");
            if (sensitive) {
                rejects([&] { (void)parse_clip(captured, profile37); });
                auto marked = captured; marked[148] = 1; rejects([&] { (void)parse_clip(marked, vendor37); });
            }
            if (!sensitive) {
                for (size_t i = 0; i < captured.size(); ++i) rejects([&] { (void)parse_clip(std::span(captured).first(i), vendor37); });
                for (size_t i = 0; i < captured.size(); ++i) { auto changed = captured; changed[i] ^= 0xff; try { auto v = parse_clip(changed, vendor37); require(v.sensitive || valid_text(v.text)); } catch (const Failure&) {} }
            }
        }
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
        History ipc; require(ipc.add({1,"first",false}));
        require(app_reply("LIST", ipc, true, []{}).find("first")!=std::string::npos);
        require(app_reply("GET 1:2 1", ipc, true, []{}).find("first")!=std::string::npos);
        rejects([&]{app_reply("GET 1:1 1",ipc,true,[]{});});
        rejects([&]{app_reply("GET 1:2 999",ipc,true,[]{});});
        rejects([&]{app_reply("GET 1:2 -1",ipc,true,[]{});});
        rejects([&]{app_reply("GET 1:2 1 extra",ipc,true,[]{});});
        rejects([&]{app_reply(std::string(129,'x'),ipc,true,[]{});});
        int saved=0;
        require(app_reply("CLEAR 1:2",ipc,true,[&]{++saved;}).find("true")!=std::string::npos);
        require(saved==1 && ipc.entries().empty() && ipc.generation==2);
        rejects([&]{app_reply("CLEAR 1:2",ipc,true,[]{});});
        auto lp=[](std::vector<uint8_t> data) { std::vector<uint8_t> out;append_u32(out,static_cast<uint32_t>(data.size()));out.insert(out.end(),data.begin(),data.end());return out; };
        auto concat=[](std::vector<uint8_t> a,const std::vector<uint8_t>& b){a.insert(a.end(),b.begin(),b.end());return a;};
        auto signed_data=concat(concat(lp({}),lp(lp({1,2,3}))),lp({}));
        auto signer=concat(concat(lp(signed_data),lp({})),lp({}));
        auto block=lp(lp(signer));
        require(apk_v2_certificate(block)==std::vector<uint8_t>({1,2,3}));
        auto reserved_data = concat(signed_data,lp({}));
        auto reserved_signer = concat(concat(lp(reserved_data),lp({})),lp({}));
        require(apk_v2_certificate(lp(lp(reserved_signer)))==std::vector<uint8_t>({1,2,3}));
        reserved_data.back()=1;
        reserved_signer=concat(concat(lp(reserved_data),lp({})),lp({}));
        rejects([&]{apk_v2_certificate(lp(lp(reserved_signer)));});
        for(size_t n=0;n<block.size();++n) rejects([&]{apk_v2_certificate(std::span(block).first(n));});
        rejects([&]{apk_v2_certificate(lp(concat(lp(signer),lp(signer))));});
        std::cout << checks << " assertions passed\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
