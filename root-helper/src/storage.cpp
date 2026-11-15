#include "platform.hpp"
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace sayin {
namespace {
void check(bool ok, const char* message) { if (!ok) throw Failure(message); }
}
Store::Store(const std::string& path) {
    check(!path.empty() && path[0] == '/' && path.back() != '/', "data_path");
    // Walk every component with NOFOLLOW. Never chown or chmod an existing unknown directory.
    fd_ = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    size_t start = 1;
    try {
        while (start < path.size()) {
            const auto end = path.find('/', start);
            const auto component = path.substr(start, end == std::string::npos ? end : end-start);
            check(!component.empty() && component != "." && component != "..", "data_path_component");
            const bool last = end == std::string::npos;
            if (last && mkdirat(fd_, component.c_str(), 0700) != 0 && errno != EEXIST) throw Failure("data_mkdir");
            const int next = openat(fd_, component.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            check(next >= 0, "data_directory"); close(fd_); fd_ = next;
            if (last) break;
            start = end + 1;
        }
        struct stat st{}; check(fstat(fd_, &st) == 0 && st.st_uid == 0 && (st.st_mode & 0777) == 0700, "data_permissions");
    } catch (...) { if (fd_ >= 0) close(fd_); fd_ = -1; throw; }
}
Store::~Store() { if (fd_ >= 0) close(fd_); }
bool Store::exists(const char* name) const {
    struct stat st{};
    if (fstatat(fd_, name, &st, AT_SYMLINK_NOFOLLOW) == 0) return true;
    if (errno != ENOENT) throw Failure("data_stat");
    return false;
}
std::vector<uint8_t> Store::read(const char* name, size_t limit) const {
    const int fd = openat(fd_, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    check(fd >= 0, "data_read_open");
    struct stat st{};
    const bool valid = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_uid == 0 && st.st_nlink == 1 &&
        (st.st_mode & 0777) == 0600 && st.st_size >= 0 && static_cast<uint64_t>(st.st_size) <= limit;
    if (!valid) { close(fd); throw Failure("data_file_permissions_or_size"); }
    std::vector<uint8_t> bytes(static_cast<size_t>(st.st_size)); size_t offset = 0;
    while (offset < bytes.size()) {
        const auto n = ::read(fd, bytes.data()+offset, bytes.size()-offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { close(fd); throw Failure("data_read"); }
        offset += static_cast<size_t>(n);
    }
    close(fd); return bytes;
}
void Store::replace(const char* name, std::span<const uint8_t> bytes) const {
    const std::string temp = std::string(name) + ".pending";
    if (unlinkat(fd_, temp.c_str(), 0) != 0 && errno != ENOENT) throw Failure("pending_cleanup");
    const int fd = openat(fd_, temp.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    check(fd >= 0, "data_write_open");
    try {
        size_t offset = 0;
        while (offset < bytes.size()) {
            const auto n = write(fd, bytes.data()+offset, bytes.size()-offset);
            if (n < 0 && errno == EINTR) continue;
            check(n > 0, "data_write"); offset += static_cast<size_t>(n);
        }
        check(fsync(fd) == 0, "data_fsync"); close(fd);
    } catch (...) { close(fd); unlinkat(fd_, temp.c_str(), 0); throw; }
    check(renameat(fd_, temp.c_str(), fd_, name) == 0, "data_rename");
    check(fsync(fd_) == 0, "directory_fsync");
}
void Store::get_key() {
    if (key_.empty()) { key_ = read("key", 32); check(key_.size() == 32, "key_length"); }
}
void Store::initialize() {
    const bool key = exists("key"), history = exists("history.enc");
    if (key != history) throw Failure("storage_incomplete");
    if (!key) { key_ = crypto_.random(32); replace("key", key_); save(History{}); }
    else (void)load();
}
History Store::load() { get_key(); return History::decode(crypto_.open(key_, read("history.enc", kMaxBytes + 4096))); }
void Store::save(const History& history) { get_key(); replace("history.enc", crypto_.seal(key_, history.encode())); }
void Store::flag(bool enable) {
    if (enable) { const std::vector<uint8_t> value{'1','\n'}; replace("enabled", value); }
    else { if (unlinkat(fd_, "enabled", 0) != 0 && errno != ENOENT) throw Failure("disable_write"); check(fsync(fd_) == 0, "disable_sync"); }
}
bool Store::enabled() const {
    if (!exists("enabled")) return false;
    const auto value = read("enabled", 2); return value == std::vector<uint8_t>{'1','\n'};
}
void Store::write_state(const std::string& state) const {
    if (state.size() > 4096) throw Failure("state_limit");
    replace("state.json", std::span(reinterpret_cast<const uint8_t*>(state.data()), state.size()));
}
}
