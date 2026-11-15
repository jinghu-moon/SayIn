#include "platform.hpp"
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <iostream>
#include <poll.h>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

namespace sayin {
namespace {
volatile sig_atomic_t interrupted = 0;
void signal_stop(int) { interrupted = 1; }
class Instance {
public:
    explicit Instance(Store& store) {
        fd = openat(store.directory(), "instance.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
        struct stat st{};
        if (fd < 0 || fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_uid || st.st_nlink != 1 || (st.st_mode & 0777) != 0600) throw Failure("instance_lock_invalid");
        if (flock(fd, LOCK_EX | LOCK_NB)) { close(fd); fd = -1; throw Failure("instance_busy"); }
    }
    ~Instance() { if (fd >= 0) close(fd); }
    int fd = -1;
};
bool live(Store& store) {
    const int fd = openat(store.directory(), "instance.lock", O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return false;
    const bool busy = flock(fd, LOCK_EX | LOCK_NB) != 0;
    close(fd); return busy;
}
void remove_file(Store& store, const char* name) {
    if (unlinkat(store.directory(), name, 0) && errno != ENOENT) throw Failure("control_cleanup");
}
int socket_server(Store& store) {
    remove_file(store, "control.sock");
    const int server = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (server < 0) throw Failure("control_socket");
    // /proc/self/fd keeps bind rooted in the verified directory without path races.
    sockaddr_un address{}; address.sun_family = AF_UNIX;
    const std::string path = "/proc/self/fd/" + std::to_string(store.directory()) + "/control.sock";
    if (path.size() >= sizeof(address.sun_path)) throw Failure("control_path");
    std::memcpy(address.sun_path, path.c_str(), path.size()+1);
    if (bind(server, reinterpret_cast<sockaddr*>(&address), sizeof(address)) || fchmodat(store.directory(), "control.sock", 0600, 0) || listen(server, 4)) {
        close(server); throw Failure("control_bind");
    }
    return server;
}
std::string client(Store& store, char command) {
    if (!live(store)) throw Failure("collector_not_running");
    const int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) throw Failure("client_socket");
    sockaddr_un address{}; address.sun_family = AF_UNIX;
    const std::string path = "/proc/self/fd/" + std::to_string(store.directory()) + "/control.sock";
    std::memcpy(address.sun_path, path.c_str(), path.size()+1);
    timeval timeout{2, 0}; setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    if (connect(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) || send(fd, &command, 1, MSG_NOSIGNAL) != 1) { close(fd); throw Failure("control_connect"); }
    std::array<char, 4096> response{};
    const auto size = recv(fd, response.data(), response.size(), 0); close(fd);
    if (size <= 0) throw Failure("control_timeout");
    return std::string(response.data(), static_cast<size_t>(size));
}
int run(const std::string& path, const std::string& module, const std::string& prefix, int duration) {
    Store store(path); Instance instance(store);
    if (!store.enabled()) throw Failure("clipboard_not_enabled");
    const int server = socket_server(store);
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair)) throw Failure("capture_channel");
    const int budget = 512 * 1024;
    setsockopt(pair[0], SOL_SOCKET, SO_RCVBUF, &budget, sizeof(budget));
    const pid_t parent = getpid(), child = fork();
    if (child < 0) throw Failure("collector_fork");
    if (child == 0) {
        close(pair[0]); close(server); close(instance.fd); close(store.directory());
        if (prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() != parent) _exit(70);
        _exit(collector(pair[1], prefix));
    }
    close(pair[1]);
    // Read the encryption key only after fork: the shell child must never inherit it.
    History history;
    try { history = store.load(); }
    catch (...) { kill(child, SIGTERM); waitpid(child, nullptr, 0); close(pair[0]); close(server); remove_file(store, "control.sock"); throw; }
    signal(SIGTERM, signal_stop); signal(SIGINT, signal_stop); signal(SIGPIPE, SIG_IGN);
    uint64_t accepted = 0, dropped = 0; bool ready = false, fatal = false, stop = false;
    const auto started = std::chrono::steady_clock::now();
    const auto status = [&] {
        return "{\"running\":true,\"backend\":\"binder_shell\",\"capture_ready\":" + std::string(ready ? "true" : "false") +
            ",\"accepted\":" + std::to_string(accepted) + ",\"dropped_or_coalesced\":" + std::to_string(dropped) +
            ",\"entries\":" + std::to_string(history.entries().size()) + ",\"generation\":" + std::to_string(history.generation) + ",\"keepalive\":\"not_implemented\"}";
    };
    try {
        while (!interrupted && !stop) {
            if (duration > 0 && std::chrono::steady_clock::now()-started >= std::chrono::seconds(duration)) break;
            if (!store.enabled() || (!module.empty() && (access((module+"/disable").c_str(), F_OK) == 0 || access((module+"/remove").c_str(), F_OK) == 0))) break;
            pollfd fds[2]{{server, POLLIN, 0}, {pair[0], POLLIN, 0}};
            int result; do { result = poll(fds, 2, 1000); } while (result < 0 && errno == EINTR && !interrupted);
            if (result < 0) { if (interrupted) break; throw Failure("control_poll"); }
            // Commands precede queued capture: clear/stop form a single-writer barrier.
            if (fds[0].revents & POLLIN) {
                int client_fd = accept4(server, nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK);
                if (client_fd >= 0) {
                    ucred peer{}; socklen_t length = sizeof(peer);
                    char command[2]{};
                    if (getsockopt(client_fd, SOL_SOCKET, SO_PEERCRED, &peer, &length) == 0 && peer.uid == 0) {
                        pollfd incoming{client_fd, POLLIN, 0}; poll(&incoming, 1, 100);
                        const auto size = recv(client_fd, command, sizeof(command), 0);
                        std::string response;
                        if (size != 1) response = "{\"error\":\"invalid_command\"}";
                        else if (command[0] == 'S') response = status();
                        else if (command[0] == 'Q') { store.flag(false); stop = true; response = "{\"stopping\":true}"; }
                        else if (command[0] == 'T') { stop = true; response = "{\"stopping\":true}"; }
                        else if (command[0] == 'C') {
                            history.clear();
                            history.last_timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
                            store.save(history);
                            const char barrier = 'B'; (void)send(pair[0], &barrier, 1, MSG_NOSIGNAL | MSG_DONTWAIT);
                            response = "{\"cleared\":true,\"generation\":" + std::to_string(history.generation) + "}";
                        } else response = "{\"error\":\"unsupported_command\"}";
                        (void)send(client_fd, response.data(), response.size(), MSG_NOSIGNAL | MSG_DONTWAIT);
                    }
                    close(client_fd);
                }
            }
            if (stop) break;
            if (fds[1].revents & POLLIN) {
                Packet packet{}; const auto size = recv(pair[0], &packet, sizeof(packet), MSG_DONTWAIT | MSG_TRUNC);
                if (size != sizeof(packet)) throw Failure("capture_packet");
                if (packet.kind == 1) {
                    if (packet.size > kMaxText) throw Failure("capture_length");
                    Clip clip{packet.timestamp, std::string(packet.text, packet.size), false};
                    if (history.add(clip)) { store.save(history); ++accepted; }
                } else if (packet.kind == 2) ready = packet.state == 1;
                else if (packet.kind == 3) dropped += packet.state;
                else { fatal = true; break; }
            } else if (fds[1].revents & (POLLHUP | POLLERR)) { fatal = true; break; }
        }
    } catch (...) { fatal = true; }
    const char quit = 'Q'; (void)send(pair[0], &quit, 1, MSG_DONTWAIT | MSG_NOSIGNAL);
    bool reaped = false;
    for (int i = 0; i < 20; ++i) {
        if (waitpid(child, nullptr, WNOHANG) == child) { reaped = true; break; }
        usleep(50000);
    }
    if (!reaped) { kill(child, SIGTERM); waitpid(child, nullptr, 0); }
    close(pair[0]); close(server); remove_file(store, "control.sock");
    store.write_state("{\"running\":false,\"last_exit\":\"" + std::string(fatal ? "capture_fault" : "stopped") + "\",\"accepted\":" + std::to_string(accepted) + ",\"dropped_or_coalesced\":" + std::to_string(dropped) + "}\n");
    std::cout << "{\"running\":false,\"accepted\":" << accepted << ",\"dropped_or_coalesced\":" << dropped << ",\"entries\":" << history.entries().size() << "}\n";
    if (fatal) throw Failure("capture_stopped_on_fault");
    return 0;
}
}
}

int main(int argc, char** argv) {
    using namespace sayin;
    try {
        if (argc < 2) throw Failure("usage_required");
        const std::string command = argv[1];
        std::string path = "/data/adb/sayin-helper", module, prefix;
        int duration = 0;
        for (int i = 2; i < argc; ++i) {
            const std::string option = argv[i];
            if (++i >= argc) throw Failure("missing_argument");
            if (option == "--data-dir") path = argv[i];
            else if (option == "--module-dir") module = argv[i];
            else if (option == "--test-prefix") { prefix = argv[i]; if (!prefix.starts_with("SAYIN_TEST_") || prefix.size() > 80) throw Failure("test_prefix"); }
            else if (option == "--duration") { duration = std::stoi(argv[i]); if (duration < 1 || duration > 600) throw Failure("duration_limit"); }
            else throw Failure("unknown_argument");
        }
        if (prefix.size() && (path == "/data/adb/sayin-helper" || duration == 0)) throw Failure("test_requires_isolated_directory_and_duration");
        if (command == "probe") { probe(); std::cout << "{\"clipboard_binder_access\":true}\n"; return 0; }
        if (getuid() != 0) throw Failure("root_required");
        umask(0077); if (prctl(PR_SET_DUMPABLE, 0)) throw Failure("process_protection");
        if (command == "crypto-test") { Crypto{}.self_test(); std::cout << "{\"aes_gcm_and_tamper_test\":true}\n"; return 0; }
        if (command == "run") return run(path, module, prefix, duration);
        Store store(path);
        if (command == "status") {
            if (live(store)) std::cout << client(store, 'S') << '\n';
            else std::cout << "{\"running\":false,\"enabled\":" << (store.enabled() ? "true" : "false") << ",\"keepalive\":\"not_implemented\"}\n";
            return 0;
        }
        if (command == "disable" || command == "stop") {
            if (live(store)) {
                std::cout << client(store, command == "disable" ? 'Q' : 'T') << '\n';
                bool stopped = false;
                for (int i = 0; i < 60; ++i) {
                    if (!live(store)) { stopped = true; break; }
                    usleep(50000);
                }
                if (!stopped) throw Failure("stop_not_acknowledged");
            }
            else { Instance lock(store); if (command == "disable") store.flag(false); std::cout << "{\"stopped\":true}\n"; }
            return 0;
        }
        if (command == "clear" && live(store)) { std::cout << client(store, 'C') << '\n'; return 0; }
        Instance lock(store);
        if (command == "purge") {
            if (store.enabled()) throw Failure("disable_before_purge");
            // Remove only this implementation's known files, never recursively delete a path.
            for (const auto* name : {"key", "history.enc", "history.enc.pending", "key.pending", "enabled.pending", "control.sock", "state.json", "state.json.pending"}) remove_file(store, name);
            if (fsync(store.directory())) throw Failure("purge_sync");
            std::cout << "{\"history_and_key_removed\":true}\n";
        }
        else if (command == "enable") { store.initialize(); store.flag(true); std::cout << "{\"enabled\":true}\n"; }
        else if (command == "list") std::cout << store.load().json() << '\n';
        else if (command == "clear") {
            auto h = store.load(); h.clear();
            h.last_timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
            store.save(h); std::cout << "{\"cleared\":true}\n";
        }
        else throw Failure("unsupported_command");
        return 0;
    } catch (const Failure& failure) { std::cerr << "{\"error\":" << json_string(failure.what()) << "}\n"; return 69; }
    catch (...) { std::cerr << "{\"error\":\"internal_failure\"}\n"; return 70; }
}
