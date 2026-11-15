#include "app_bridge.hpp"
#include <algorithm>
#include <charconv>
#include <sstream>

namespace sayin {
namespace {
uint64_t number(const std::string& s) {
    uint64_t value = 0;
    const auto result = std::from_chars(s.data(), s.data()+s.size(), value);
    if (s.empty() || result.ec != std::errc{} || result.ptr != s.data()+s.size()) throw Failure("invalid_request");
    return value;
}
std::string preview(const std::string& text) {
    size_t end = std::min<size_t>(text.size(), 160);
    while (end < text.size() && (static_cast<unsigned char>(text[end]) & 0xc0) == 0x80) --end;
    return text.substr(0, end);
}
}
std::string app_reply(const std::string& request, History& history, bool ready, const std::function<void()>& persist) {
    if (request.empty() || request.size() > 128) throw Failure("invalid_request");
    std::istringstream stream(request); std::vector<std::string> args; std::string arg;
    while (stream >> arg) args.push_back(arg);
    if (args.empty()) throw Failure("invalid_request");
    const std::string token = std::to_string(history.generation)+":"+std::to_string(history.next_id);
    const auto base = "{\"protocol\":1,\"revision\":" + json_string(token);
    if (args[0] == "LIST" && args.size() == 1) {
        std::string out = base + ",\"capture_ready\":" + (ready ? "true" : "false") + ",\"entries\":[";
        bool first = true;
        for (const auto& entry : history.entries()) {
            if (!first) out += ',';
            first = false;
            out += "{\"id\":" + std::to_string(entry.id) + ",\"timestamp\":" + std::to_string(entry.timestamp) + ",\"preview\":" + json_string(preview(entry.text)) + "}";
        }
        return out + "]}";
    }
    if (args.size() < 2 || args[1] != token) throw Failure("stale_revision");
    if (args[0] == "GET" && args.size() == 3) {
        const auto id = number(args[2]);
        for (const auto& entry : history.entries()) if (entry.id == id) return base + ",\"text\":" + json_string(entry.text) + "}";
        throw Failure("entry_missing");
    }
    if (args[0] == "CLEAR" && args.size() == 2) {
        history.clear();
        persist();
        return "{\"protocol\":1,\"cleared\":true}";
    }
    throw Failure("invalid_request");
}

// APK v2 block is supplied by the Root-only installed-APK reader. This extracts exactly one
// current signer certificate. Installed reader rejects v3/unknown schemes so Android must
// have verified this v2 signer, rather than a different signer in another scheme.
std::vector<uint8_t> apk_v2_certificate(std::span<const uint8_t> block) {
    auto take = [](std::span<const uint8_t>& bytes) {
        if (bytes.size() < 4) throw Failure("apk_length");
        uint32_t size = 0; for (int i=0;i<4;++i) size |= uint32_t(bytes[i]) << (8*i);
        bytes = bytes.subspan(4);
        if (size > bytes.size()) throw Failure("apk_length");
        auto result = bytes.first(size); bytes = bytes.subspan(size); return result;
    };
    auto signers = take(block); if (!block.empty()) throw Failure("apk_signers");
    auto signer = take(signers); if (!signers.empty()) throw Failure("apk_multiple_signers");
    auto data = take(signer); (void)take(signer); (void)take(signer); if (!signer.empty()) throw Failure("apk_signer");
    (void)take(data); auto certs = take(data); (void)take(data);
    // Current AOSP apksig V2SchemeSigner appends a fourth empty length-prefixed field.
    // Accept that exact reserved field, not arbitrary trailing bytes.
    if (data.size() == 4 && std::all_of(data.begin(), data.end(), [](uint8_t b) { return b == 0; })) data = {};
    if (!data.empty()) throw Failure("apk_signed_data");
    auto cert = take(certs);
    if (cert.empty() || cert.size()>16384) throw Failure("apk_certificate");
    // Additional chain certificates are not additional signers. Bound and parse all lengths.
    while (!certs.empty()) (void)take(certs);
    return {cert.begin(), cert.end()};
}
}
