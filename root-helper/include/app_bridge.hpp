#pragma once
#include "platform.hpp"
namespace sayin {
// Fixed, one-shot, bounded protocol. No client-supplied package, path or certificate.
constexpr size_t kAppFrame = 128 * 1024;
int app_server(const std::string& name);
void serve_app(int server, Store& store, History& history, bool ready, int capture_channel, const std::function<bool()>& active);
bool history_access_allowed();
bool trusted_app(unsigned uid);
std::vector<uint8_t> apk_v2_certificate(std::span<const uint8_t> block);
std::string app_reply(const std::string& request, History& history, bool ready, const std::function<void()>& persist);
}
