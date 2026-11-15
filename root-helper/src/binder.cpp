#include "platform.hpp"
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <atomic>
#include <chrono>
#include <csignal>
#include <algorithm>
#include <cstring>
#include <dlfcn.h>
#include <grp.h>
#include <poll.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <unistd.h>

namespace sayin {
namespace {
template<class T> T api(void* lib, const char* name) {
    const auto function = reinterpret_cast<T>(dlsym(lib, name));
    if (!function) throw Failure("binder_api_unavailable");
    return function;
}
void check(binder_status_t status) { if (status != STATUS_OK) throw Failure("binder_transaction_" + std::to_string(status)); }
void* construct(void* data) { return data; }
void destroy(void*) {}
binder_status_t no_transaction(AIBinder*, transaction_code_t, const AParcel*, AParcel*) { return STATUS_UNKNOWN_TRANSACTION; }
struct Remote {
    AIBinder* binder = nullptr;
    Remote(void* lib, const char* service, const char* descriptor) {
        binder = api<AIBinder* (*)(const char*)>(lib, "AServiceManager_checkService")(service);
        if (!binder) throw Failure("system_service_unavailable");
        auto* cls = AIBinder_Class_define(descriptor, construct, destroy, no_transaction);
        if (!cls || !AIBinder_associateClass(binder, cls)) throw Failure("system_descriptor_mismatch");
    }
    ~Remote() { if (binder) AIBinder_decStrong(binder); }
    std::vector<uint8_t> call(uint32_t code, const std::function<void(AParcel*)>& write) {
        AParcel* input = nullptr; AParcel* output = nullptr;
        check(AIBinder_prepareTransaction(binder, &input));
        try { write(input); } catch (...) { AParcel_delete(input); throw; }
        const auto status = AIBinder_transact(binder, code, &input, &output, 0);
        if (status != STATUS_OK) { if (output) AParcel_delete(output); throw Failure("system_transaction_failed"); }
        try {
            AStatus* response = nullptr;
            const auto header = AParcel_readStatusHeader(output, &response);
            if (header != STATUS_OK) throw Failure("response_header_code_" + std::to_string(code) + "_status_" + std::to_string(header));
            const bool ok = response && AStatus_isOk(response);
            if (response) AStatus_delete(response);
            if (!ok) throw Failure("system_access_denied");
            const int size = AParcel_getDataSize(output), pos = AParcel_getDataPosition(output);
            if (size < pos || size > static_cast<int>(kMaxParcel)) throw Failure("parcel_size");
            std::vector<uint8_t> bytes(size);
            check(AParcel_marshal(output, bytes.data(), 0, bytes.size()));
            bytes.erase(bytes.begin(), bytes.begin() + pos);
            AParcel_delete(output); return bytes;
        } catch (...) { AParcel_delete(output); throw; }
    }
};
int32_t integer(std::span<const uint8_t> bytes) {
    if (bytes.size() != 4) throw Failure("system_layout");
    return int32_t(uint32_t(bytes[0]) | (uint32_t(bytes[1]) << 8) | (uint32_t(bytes[2]) << 16) | (uint32_t(bytes[3]) << 24));
}
void args(AParcel* parcel, const PlatformProfile& profile) {
    check(AParcel_writeString(parcel, "com.android.shell", 17));
    if (profile.clipboard_attribution) check(AParcel_writeString(parcel, nullptr, -1));
    check(AParcel_writeInt32(parcel, 0));
    if (profile.clipboard_attribution) check(AParcel_writeInt32(parcel, 0));
}
bool unlocked(Remote& trust, Remote& activity, Remote& window, Remote& power, const PlatformProfile& profile) {
    char value[PROP_VALUE_MAX]{};
    if (__system_property_get("sys.user.0.ce_available", value) <= 0 || std::string(value) != "true") return false;
    const auto user = activity.call(profile.current_user_transaction, [](AParcel*) {});
    if (integer(user) != 0) return false;
    const auto lock = trust.call(10, [](AParcel* p) { check(AParcel_writeInt32(p, 0)); check(AParcel_writeInt32(p, 0)); });
    const auto keyguard = window.call(30, [](AParcel*) {});
    const auto interactive = power.call(profile.interactive_transaction, [](AParcel*) {});
    return capture_allowed(true, integer(user), integer(lock), integer(keyguard), integer(interactive));
}
void shell_identity() {
    const auto parent = getppid();
    if (getuid() == 0) {
        if (setgroups(0, nullptr) || setresgid(2000, 2000, 2000) || setresuid(2000, 2000, 2000)) throw Failure("shell_identity_failed");
    }
    if (getuid() != 2000 || geteuid() != 2000 || prctl(PR_SET_DUMPABLE, 0) || prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) throw Failure("shell_required");
    // setresuid clears PDEATHSIG; reinstall it after dropping privileges.
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() != parent) throw Failure("parent_changed");
}
PlatformProfile platform() {
    char sdk[PROP_VALUE_MAX]{};
    __system_property_get("ro.build.version.sdk", sdk);
    auto profile = platform_profile(std::stoi(sdk));
    char vendor[PROP_VALUE_MAX]{}, os[PROP_VALUE_MAX]{};
    __system_property_get("ro.product.manufacturer", vendor);
    __system_property_get("ro.mi.os.version.name", os);
    // This ROM appends mHasIntent outside each non-empty BaseBundle payload.
    // AOSP does not. Keep the dialect explicit; never guess by consuming padding.
    if (profile.api == 37 && std::string(vendor) == "Xiaomi") {
        if (std::string(os) != "OS4.0") throw Failure("unsupported_vendor_parcel");
        profile.bundle_intent_marker = true;
    }
    return profile;
}
int64_t millis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
}
}

int probe() {
    const auto profile = platform(); shell_identity();
    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) throw Failure("binder_unavailable");
    Remote clipboard(lib, "clipboard", "android.content.IClipboard");
    Remote trust(lib, "trust", "android.app.trust.ITrustManager");
    Remote activity(lib, "activity", "android.app.IActivityManager");
    Remote window(lib, "window", "android.view.IWindowManager");
    Remote power(lib, "power", "android.os.IPowerManager");
    if (!unlocked(trust, activity, window, power, profile)) throw Failure("locked_or_non_owner_user");
    const auto description = clipboard.call(5, [&](AParcel* p) { args(p, profile); });
    // Do not print, export or store existing clipboard metadata/content.
    if (description.empty()) throw Failure("clipboard_layout_unavailable");
    return 0;
}

int collector(int channel, const std::string& test_prefix) {
    try {
        const auto profile = platform(); shell_identity();
        void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) throw Failure("binder_unavailable");
        Remote clipboard(lib, "clipboard", "android.content.IClipboard");
        Remote trust(lib, "trust", "android.app.trust.ITrustManager");
        Remote activity(lib, "activity", "android.app.IActivityManager");
        Remote window(lib, "window", "android.view.IWindowManager");
        Remote power(lib, "power", "android.os.IPowerManager");
        struct Callback { std::atomic<uint32_t> pending{0}; } callback;
        auto on_event = [](AIBinder* binder, transaction_code_t code, const AParcel*, AParcel*) -> binder_status_t {
            if (code != 1 || AIBinder_getCallingUid() != 1000) return STATUS_PERMISSION_DENIED;
            auto* data = static_cast<Callback*>(AIBinder_getUserData(binder));
            uint32_t current = data->pending.load();
            while (current < 64 && !data->pending.compare_exchange_weak(current, current+1)) {}
            return STATUS_OK;
        };
        auto* cls = AIBinder_Class_define("android.content.IOnPrimaryClipChangedListener", construct, destroy, on_event);
        AIBinder* listener = AIBinder_new(cls, &callback);
        if (!listener) throw Failure("listener_init");
        auto listen_args = [&](AParcel* p) { check(AParcel_writeStrongBinder(p, listener)); args(p, profile); };
        int binder_fd = -1;
        check(api<binder_status_t (*)(int*)>(lib, "ABinderProcess_setupPolling")(&binder_fd));
        auto handle = api<void (*)()>(lib, "ABinderProcess_handlePolledCommands");
        bool registered = false, active = false;
        int64_t baseline = millis(); uint64_t pending_drops = 0;
        auto send_packet = [&](Packet& packet) {
            const auto result = send(channel, &packet, sizeof(packet), MSG_DONTWAIT | MSG_NOSIGNAL);
            return result == static_cast<ssize_t>(sizeof(packet));
        };
        auto state = [&](uint32_t code) { Packet p; p.kind = 2; p.state = code; return send_packet(p); };
        try {
            (void)clipboard.call(7, listen_args); registered = true;
            auto last_gate = std::chrono::steady_clock::time_point{};
            for (;;) {
                pollfd descriptors[2]{{channel, POLLIN, 0}, {binder_fd, POLLIN, 0}};
                int result;
                do { result = poll(descriptors, 2, 1000); } while (result < 0 && errno == EINTR);
                if (result < 0 || (descriptors[0].revents & (POLLHUP | POLLERR))) break;
                if (descriptors[0].revents & POLLIN) {
                    char command = 0;
                    if (recv(channel, &command, 1, 0) != 1 || command == 'Q') break;
                    if (command == 'B') { baseline = millis(); callback.pending = 0; }
                }
                // Check gate before dispatch/read. Never store a snapshot collected while locked.
                const auto now = std::chrono::steady_clock::now();
                if (now - last_gate >= std::chrono::seconds(1)) {
                    const bool allow = unlocked(trust, activity, window, power, profile);
                    if (allow != active) { baseline = millis(); callback.pending = 0; }
                    active = allow; last_gate = now;
                    if (pending_drops) {
                        Packet p; p.kind = 3; p.state = static_cast<uint32_t>(std::min<uint64_t>(pending_drops, UINT32_MAX));
                        if (send_packet(p)) pending_drops -= p.state;
                    }
                    if (!state(active ? 1 : 0)) { ++pending_drops; continue; }
                }
                if (descriptors[1].revents & POLLIN) handle();
                const uint32_t count = callback.pending.exchange(0);
                if (!count || !active) continue;
                if (!unlocked(trust, activity, window, power, profile)) { active = false; baseline = millis(); continue; }
                // A listener carries no per-event payload. Multiple queued callbacks may already
                // refer to the last value: expose coalescing, never claim lossless burst capture.
                if (count > 1) pending_drops += count - 1;
                const auto bytes = clipboard.call(4, [&](AParcel* p) { args(p, profile); });
                try {
                    auto clip = parse_clip(bytes, profile);
                    if (!unlocked(trust, activity, window, power, profile)) { active = false; baseline = millis(); continue; }
                    if (clip.timestamp <= baseline) continue;
                    Packet p;
                    if (clip.sensitive) { p.kind = 3; p.state = 1; }
                    else if (!test_prefix.empty() && !clip.text.starts_with(test_prefix)) continue;
                    else {
                        p.kind = 1; p.timestamp = clip.timestamp; p.size = static_cast<uint32_t>(clip.text.size());
                        std::memcpy(p.text, clip.text.data(), p.size);
                    }
                    if (!send_packet(p)) ++pending_drops;
                } catch (const Failure&) { ++pending_drops; }
            }
        } catch (...) {
            if (registered) { try { (void)clipboard.call(8, listen_args); } catch (...) {} }
            AIBinder_decStrong(listener); throw;
        }
        if (registered) { try { (void)clipboard.call(8, listen_args); } catch (...) {} }
        AIBinder_decStrong(listener);
        close(channel); return 0;
    } catch (const Failure&) {
        Packet packet; packet.kind = 4; (void)send(channel, &packet, sizeof(packet), MSG_DONTWAIT | MSG_NOSIGNAL);
        close(channel); return 69;
    }
}
}
