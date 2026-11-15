#include "app_bridge.hpp"
#include "app_policy.hpp"
#include <array>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

namespace sayin {
namespace {
// No shell. Fixed executable/arguments; UID is from SO_PEERCRED, never from the request.
std::string package_cmd(const std::vector<std::string>& args) {
    int pair[2]; if (pipe2(pair, O_CLOEXEC | O_NONBLOCK)) throw Failure("package_pipe");
    const pid_t pid = fork();
    if (pid < 0) { close(pair[0]); close(pair[1]); throw Failure("package_fork"); }
    if (!pid) {
        close(pair[0]); dup2(pair[1], STDOUT_FILENO); close(pair[1]);
        const int null = open("/dev/null", O_WRONLY); if (null>=0) { dup2(null, STDERR_FILENO); close(null); }
        std::vector<char*> argv{const_cast<char*>("/system/bin/cmd"), const_cast<char*>("package")};
        for (const auto& arg : args) argv.push_back(const_cast<char*>(arg.c_str()));
        argv.push_back(nullptr); execv(argv[0], argv.data()); _exit(71);
    }
    close(pair[1]); std::string out; bool done=false; int status=0;
    const auto limit = std::chrono::steady_clock::now()+std::chrono::milliseconds(800);
    while (std::chrono::steady_clock::now()<limit && out.size()<8192) {
        std::array<char,1024> buffer{}; const auto n = read(pair[0],buffer.data(),buffer.size());
        if (n>0) out.append(buffer.data(),static_cast<size_t>(n));
        if (n==0) { done=true; break; }
        if (n<0 && errno!=EAGAIN && errno!=EINTR) break;
        pollfd p{pair[0],POLLIN,0}; poll(&p,1,10);
    }
    close(pair[0]);
    if (!done) kill(pid,SIGKILL);
    bool reaped=false;
    while (std::chrono::steady_clock::now()<limit) {
        if (waitpid(pid,&status,WNOHANG)==pid) { reaped=true; break; }
        usleep(1000);
    }
    if (!reaped) { kill(pid,SIGKILL); waitpid(pid,&status,0); done=false; }
    if (!done || !WIFEXITED(status) || WEXITSTATUS(status)!=0 || out.size()>=8192) throw Failure("package_lookup");
    return out;
}
uint64_t little(std::span<const uint8_t> bytes) {
    uint64_t value=0; for (size_t i=0;i<bytes.size();++i) value|=uint64_t(bytes[i])<<(8*i); return value;
}
std::vector<uint8_t> read_at(int fd, uint64_t offset, size_t size) {
    std::vector<uint8_t> out(size); size_t pos=0;
    while (pos<size) {
        const auto n=pread(fd,out.data()+pos,size-pos,static_cast<off_t>(offset+pos));
        if (n<0 && errno==EINTR) continue;
        if (n<=0) throw Failure("apk_read");
        pos+=static_cast<size_t>(n);
    }
    return out;
}
std::string certificate_digest(const std::string& path) {
    if (!path.starts_with("/data/app/") || !path.ends_with("/base.apk") || path.find("..")!=std::string::npos) throw Failure("apk_path");
    // /data/app trees and installed APKs are PackageManager-owned, not caller-controlled.
    int fd=open(path.c_str(),O_RDONLY|O_NOFOLLOW|O_CLOEXEC); if(fd<0) throw Failure("apk_open");
    try {
        struct stat st{};
        if(fstat(fd,&st) || !S_ISREG(st.st_mode) || (st.st_uid!=1000 && st.st_uid!=0) || (st.st_mode&0022) || st.st_size<22) throw Failure("apk_owner");
        const auto length=static_cast<uint64_t>(st.st_size);
        const auto tail=read_at(fd,length-std::min<uint64_t>(65557,length),std::min<uint64_t>(65557,length));
        size_t eocd=tail.size();
        for(size_t i=tail.size()-22;;--i) {
            if(little(std::span(tail).subspan(i,4))==0x06054b50 && i+22+little(std::span(tail).subspan(i+20,2))==tail.size()) { eocd=i; break; }
            if(i==0) break;
        }
        if(eocd==tail.size() || little(std::span(tail).subspan(eocd+4,4))!=0) throw Failure("apk_eocd");
        const auto central=little(std::span(tail).subspan(eocd+16,4));
        if(central<32 || central>length-22) throw Failure("apk_central");
        const auto footer=read_at(fd,central-24,24);
        if(std::memcmp(footer.data()+8,"APK Sig Block 42",16)) throw Failure("apk_signing_block");
        const auto size=little(std::span(footer).first(8));
        if(size<24 || size>1024*1024 || size+8>central) throw Failure("apk_signing_size");
        const auto block=read_at(fd,central-size-8,static_cast<size_t>(size+8));
        if(little(std::span(block).first(8))!=size) throw Failure("apk_signing_header");
        std::vector<uint8_t> cert;
        size_t pos=8;
        while(pos<block.size()-24) {
            if(block.size()-24-pos<8) throw Failure("apk_pair");
            const auto count=little(std::span(block).subspan(pos,8)); pos+=8;
            if(count<4 || count>block.size()-24-pos) throw Failure("apk_pair");
            const auto block_id = little(std::span(block).subspan(pos,4));
            // Android must have verified THIS v2 signer. A forged v2 certificate alongside a
            // valid attacker v3 signer is not authority. Explicitly admit only v2 + padding;
            // reject v3/v3.1/unknown schemes rather than inferring which PM selected.
            if (block_id != 0x7109871a && block_id != 0x42726577) throw Failure("apk_v2_only_required");
            if(block_id==0x7109871a) {
                if(!cert.empty()) throw Failure("apk_duplicate_v2");
                cert=apk_v2_certificate(std::span(block).subspan(pos+4,count-4));
            }
            pos+=static_cast<size_t>(count);
        }
        if(cert.empty() || pos!=block.size()-24) throw Failure("apk_v2_required");
        close(fd); fd=-1;
        void* lib=dlopen("/system/lib64/libcrypto.so",RTLD_NOW|RTLD_LOCAL); if(!lib) throw Failure("digest_api");
        const auto sha=reinterpret_cast<uint8_t*(*)(const uint8_t*,size_t,uint8_t*)>(dlsym(lib,"SHA256"));
        std::array<uint8_t,32> digest{}; const bool ok=sha && sha(cert.data(),cert.size(),digest.data()); dlclose(lib);
        if(!ok) throw Failure("digest_api");
        const char* hex="0123456789abcdef"; std::string result;
        for(auto b:digest) { result+=hex[b>>4]; result+=hex[b&15]; } return result;
    } catch (...) { if(fd>=0) close(fd); throw; }
}
bool transfer(int fd, void* buffer, size_t count, bool writing) {
    auto* bytes=static_cast<uint8_t*>(buffer); size_t pos=0;
    const auto end=std::chrono::steady_clock::now()+std::chrono::milliseconds(500);
    while(pos<count && std::chrono::steady_clock::now()<end) {
        pollfd p{fd,static_cast<short>(writing?POLLOUT:POLLIN),0};
        if(poll(&p,1,20)<=0) continue;
        auto n=writing?send(fd,bytes+pos,count-pos,MSG_NOSIGNAL|MSG_DONTWAIT):recv(fd,bytes+pos,count-pos,MSG_DONTWAIT);
        if(n<0 && (errno==EINTR || errno==EAGAIN)) continue;
        if(n<=0) return false;
        pos+=static_cast<size_t>(n);
    }
    return pos==count;
}
}
namespace {
bool check_app(unsigned uid) {
    if(uid<10000 || uid>=100000) return false;
    const auto id=std::to_string(uid);
    const auto packages=package_cmd({"list","packages","-U","--uid",id,"--user","0"});
    std::string package;
    for(const auto* allowed : {kAllowedApp,kAllowedTestApp})
        if(packages=="package:"+std::string(allowed)+" uid:"+id+"\n") package=allowed;
    if(package.empty()) return false; // Deny shared UIDs, work profiles and unrelated packages.
    auto paths=package_cmd({"path","--user","0",package});
    if(!paths.starts_with("package:")) return false;
    const auto end=paths.find('\n'); if(end==std::string::npos) return false;
    const auto path=paths.substr(8,end-8);
    if(certificate_digest(path)!=kAppPin) return false;
    // No UID cache. Recheck mapping after opening the authoritative installed APK.
    return package_cmd({"list","packages","-U","--uid",id,"--user","0"})==packages;
}
}
bool trusted_app(unsigned uid) {
    try { return check_app(uid); } catch (...) { return false; }
}
int app_server(const std::string& name) {
    if(name.empty()) return -1;
    const int fd=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0);
    sockaddr_un addr{}; addr.sun_family=AF_UNIX;
    if(fd<0 || name.size()+1>=sizeof(addr.sun_path)) { if(fd>=0) close(fd); throw Failure("app_socket"); }
    std::memcpy(addr.sun_path+1,name.data(),name.size());
    if(bind(fd,reinterpret_cast<sockaddr*>(&addr),static_cast<socklen_t>(offsetof(sockaddr_un,sun_path)+1+name.size())) || listen(fd,4)) {close(fd);throw Failure("app_bind");}
    return fd;
}
void serve_app(int server, Store& store, History& history, bool ready, int capture_channel, const std::function<bool()>& active) {
    const int fd=accept4(server,nullptr,nullptr,SOCK_CLOEXEC|SOCK_NONBLOCK); if(fd<0) return;
    try {
        ucred peer{};socklen_t size=sizeof(peer);
        if(getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&size) || !trusted_app(peer.uid)) throw Failure("client_not_authorized");
        uint32_t length=0;
        if(!transfer(fd,&length,4,false) || length==0 || length>128) throw Failure("invalid_request");
        std::string request(length,'\0');
        if(!transfer(fd,request.data(),length,false)) throw Failure("request_timeout");
        if(!active() || !history_access_allowed()) throw Failure("locked_or_non_owner_user");
        const auto before=history;
        const auto reply=app_reply(request,history,ready,[&] {
            history.last_timestamp=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
            try { store.save(history); } catch (...) { history=before; throw; }
            const char barrier='B';(void)send(capture_channel,&barrier,1,MSG_NOSIGNAL|MSG_DONTWAIT);
        });
        // Revalidate just before exporting content; an old connection grants no standing authority.
        if(!active() || !trusted_app(peer.uid) || !history_access_allowed()) throw Failure("client_not_authorized");
        if(reply.size()>kAppFrame) throw Failure("response_limit");
        length=static_cast<uint32_t>(reply.size());
        if(transfer(fd,&length,4,true)) (void)transfer(fd,const_cast<char*>(reply.data()),reply.size(),true);
    } catch(const Failure& e) {
        const auto reply="{\"error\":"+json_string(e.what())+"}";uint32_t n=static_cast<uint32_t>(reply.size());
        if(transfer(fd,&n,4,true)) (void)transfer(fd,const_cast<char*>(reply.data()),reply.size(),true);
    } catch(...) {} // Fail closed; never log clipboard content.
    close(fd);
}
}
