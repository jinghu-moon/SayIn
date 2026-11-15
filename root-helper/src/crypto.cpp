#include "platform.hpp"
#include <dlfcn.h>
#include <algorithm>
#include <memory>

namespace sayin {
namespace {
template<class T> T symbol(void* library, const char* name) {
    auto value = reinterpret_cast<T>(dlsym(library, name));
    if (!value) throw Failure("crypto_api_unavailable");
    return value;
}
using Algorithm = const void* (*)();
using Create = void* (*)(const void*, const uint8_t*, size_t, size_t);
using Destroy = void (*)(void*);
using Crypt = int (*)(const void*, uint8_t*, size_t*, size_t, const uint8_t*, size_t, const uint8_t*, size_t, const uint8_t*, size_t);
const std::vector<uint8_t> aad{'S','a','y','I','n','/','c','l','i','p','b','o','a','r','d','/','v','1'};
std::vector<uint8_t> crypt(void* lib, std::span<const uint8_t> key, std::span<const uint8_t> nonce, std::span<const uint8_t> input, bool seal) {
    if (key.size() != 32 || nonce.size() != 12 || input.size() > kMaxBytes + 4096) throw Failure("crypto_length");
    void* context = symbol<Create>(lib, "EVP_AEAD_CTX_new")(symbol<Algorithm>(lib, "EVP_aead_aes_256_gcm")(), key.data(), key.size(), 16);
    if (!context) throw Failure("crypto_init");
    const auto release = symbol<Destroy>(lib, "EVP_AEAD_CTX_free");
    std::unique_ptr<void, Destroy> guard(context, release);
    std::vector<uint8_t> output(input.size() + 16); size_t size = 0;
    const auto operation = symbol<Crypt>(lib, seal ? "EVP_AEAD_CTX_seal" : "EVP_AEAD_CTX_open");
    if (operation(context, output.data(), &size, output.size(), nonce.data(), nonce.size(), input.data(), input.size(), aad.data(), aad.size()) != 1) throw Failure("authentication_failed");
    output.resize(size); return output;
}
}
Crypto::Crypto() : lib_(dlopen("/system/lib64/libcrypto.so", RTLD_NOW | RTLD_LOCAL)) {
    if (!lib_) throw Failure("crypto_unavailable");
}
Crypto::~Crypto() { if (lib_) dlclose(lib_); }
std::vector<uint8_t> Crypto::random(size_t size) const {
    if (size > 64) throw Failure("random_length");
    std::vector<uint8_t> bytes(size);
    if (symbol<int (*)(uint8_t*, size_t)>(lib_, "RAND_bytes")(bytes.data(), bytes.size()) != 1) throw Failure("random_failed");
    return bytes;
}
std::vector<uint8_t> Crypto::seal(std::span<const uint8_t> key, std::span<const uint8_t> plain) const {
    auto nonce = random(12); auto cipher = crypt(lib_, key, nonce, plain, true);
    std::vector<uint8_t> result{'S','I','E','1'};
    result.insert(result.end(), nonce.begin(), nonce.end()); result.insert(result.end(), cipher.begin(), cipher.end());
    return result;
}
std::vector<uint8_t> Crypto::open(std::span<const uint8_t> key, std::span<const uint8_t> encrypted) const {
    if (encrypted.size() < 32 || !std::equal(encrypted.begin(), encrypted.begin()+4, "SIE1")) throw Failure("cipher_header");
    return crypt(lib_, key, encrypted.subspan(4, 12), encrypted.subspan(16), false);
}
void Crypto::self_test() const {
    const std::vector<uint8_t> key(32, 0), text{'s','y','n','t','h','e','t','i','c'};
    auto sealed = seal(key, text);
    if (open(key, sealed) != text) throw Failure("crypto_self_test");
    sealed.back() ^= 1;
    try { (void)open(key, sealed); } catch (const Failure&) { return; }
    throw Failure("crypto_tamper_accepted");
}
}
