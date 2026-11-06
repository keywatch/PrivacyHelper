#include "sandbox.h"
#include "hook_memory.h"
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/sensor.h>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <jni.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

using HookFunction = int (*)(void*, void*, void**);
using UnhookFunction = int (*)(void*);
using ModuleLoaded = void (*)(const char*, void*);
struct NativeAPIEntries { uint32_t version; HookFunction hook_func; UnhookFunction unhook_func; };

namespace {
constexpr char tag[] = "SystemHelperGuard";
constexpr unsigned sensors = 1, audio = 2, network = 4;
std::atomic<uint64_t> policy_mask{0};
std::atomic<unsigned> reported{0};
std::atomic<bool> started{false};
HookFunction hook_function = nullptr;
int hook_count = 0;
bool soft(unsigned bit) { return (policy_mask.load(std::memory_order_relaxed) & bit) != 0; }
bool deny(unsigned bit) { return ((policy_mask.load(std::memory_order_relaxed) >> 32) & bit) != 0; }
void hit(unsigned bit, const char* name) {
    if (!(reported.fetch_or(bit) & bit)) __android_log_print(ANDROID_LOG_INFO, tag, "native-hit adapter=%s", name);
}
bool internet(int fd) {
    int saved = errno, domain = 0; socklen_t size = sizeof(domain);
    bool result = getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &size) == 0 &&
        (domain == AF_INET || domain == AF_INET6 || domain == AF_PACKET);
    errno = saved; return result;
}
bool block_network(int fd) {
    if (!deny(network) || !internet(fd)) return false;
    hit(4, "network:deny"); errno = EACCES; return true;
}
int (*real_connect)(int, const sockaddr*, socklen_t);
ssize_t (*real_sendto)(int, const void*, size_t, int, const sockaddr*, socklen_t);
ssize_t (*real_sendmsg)(int, const msghdr*, int);
int (*real_sendmmsg)(int, mmsghdr*, unsigned, int);
int guarded_connect(int fd, const sockaddr* address, socklen_t size) {
    return block_network(fd) ? -1 : real_connect(fd, address, size);
}
ssize_t guarded_sendto(int fd, const void* data, size_t size, int flags, const sockaddr* address, socklen_t length) {
    return block_network(fd) ? -1 : real_sendto(fd, data, size, flags, address, length);
}
ssize_t guarded_sendmsg(int fd, const msghdr* message, int flags) {
    return block_network(fd) ? -1 : real_sendmsg(fd, message, flags);
}
int guarded_sendmmsg(int fd, mmsghdr* messages, unsigned count, int flags) {
    return block_network(fd) ? -1 : real_sendmmsg(fd, messages, count, flags);
}

int (*real_enable)(ASensorEventQueue*, const ASensor*);
int (*real_register)(ASensorEventQueue*, const ASensor*, int32_t, int64_t);
ssize_t (*real_events)(ASensorEventQueue*, ASensorEvent*, size_t);
int (*real_has_events)(ASensorEventQueue*);
int guarded_enable(ASensorEventQueue* queue, const ASensor* sensor) {
    if (deny(sensors)) { hit(1, "sensors:deny"); return -EPERM; }
    if (soft(sensors)) { hit(1, "sensors:no-events"); return 0; }
    return real_enable(queue, sensor);
}
int guarded_register(ASensorEventQueue* queue, const ASensor* sensor, int32_t period, int64_t latency) {
    if (deny(sensors)) { hit(1, "sensors:deny"); return -EPERM; }
    if (soft(sensors)) { hit(1, "sensors:no-events"); return 0; }
    return real_register(queue, sensor, period, latency);
}
ssize_t guarded_events(ASensorEventQueue* queue, ASensorEvent* output, size_t count) {
    if (deny(sensors)) { hit(1, "sensors:deny"); return -EPERM; }
    if (soft(sensors) && queue && (output || !count)) {
        // Drain into private storage, never copy a real sample to the caller.
        ASensorEvent discard[32];
        if (count) real_events(queue, discard, 32);
        hit(1, "sensors:no-events"); return 0;
    }
    return real_events(queue, output, count);
}
int guarded_has_events(ASensorEventQueue* queue) {
    if (deny(sensors)) return -EPERM;
    if (soft(sensors)) {
        // A queue created while policy was OFF can still wake its Looper.
        // Drain privately so suppressing hasEvents does not leave it hot forever.
        if (queue && real_events) { ASensorEvent discard[32]; real_events(queue, discard, 32); }
        return 0;
    }
    return real_has_events(queue);
}

int32_t (*real_audio_read)(AAudioStream*, void*, int32_t, int64_t);
aaudio_result_t (*real_audio_start)(AAudioStream*);
aaudio_direction_t (*audio_direction)(AAudioStream*);
aaudio_format_t (*audio_format)(AAudioStream*);
int32_t (*audio_channels)(AAudioStream*);
int32_t (*audio_rate)(AAudioStream*);
aaudio_stream_state_t (*audio_state)(AAudioStream*);
bool input(AAudioStream* stream) { return stream && audio_direction && audio_direction(stream) == AAUDIO_DIRECTION_INPUT; }
aaudio_result_t guarded_audio_start(AAudioStream* stream) {
    if (deny(audio) && input(stream)) { hit(2, "audio:deny"); return AAUDIO_ERROR_UNAVAILABLE; }
    return real_audio_start(stream);
}
int32_t guarded_audio_read(AAudioStream* stream, void* buffer, int32_t frames, int64_t timeout) {
    if (input(stream)) {
        if (deny(audio)) { hit(2, "audio:deny"); return AAUDIO_ERROR_UNAVAILABLE; }
        if (soft(audio) && buffer && frames >= 0 && timeout >= 0 && audio_format && audio_channels && audio_rate && audio_state && audio_state(stream) == AAUDIO_STREAM_STATE_STARTED) {
            int channels = audio_channels(stream), rate = audio_rate(stream), bytes = 0;
            switch (audio_format(stream)) {
                case AAUDIO_FORMAT_PCM_I16: bytes = 2; break;
                case AAUDIO_FORMAT_PCM_FLOAT: bytes = 4; break;
                case AAUDIO_FORMAT_PCM_I24_PACKED: bytes = 3; break;
                case AAUDIO_FORMAT_PCM_I32: bytes = 4; break;
                default: break;
            }
            if (bytes && channels > 0 && channels <= 32 && rate > 0) {
                // Partial reads are valid. Bound both the write and wait size.
                int32_t result = frames < 4096 ? frames : 4096;
                if (timeout > 0) {
                    int64_t nanos = static_cast<int64_t>(result) * 1000000000LL / rate;
                    if (nanos > timeout) nanos = timeout;
                    timespec delay{nanos / 1000000000LL, nanos % 1000000000LL};
                    nanosleep(&delay, nullptr);
                }
                memset(buffer, 0, static_cast<size_t>(result) * channels * bytes);
                hit(2, "audio:silent-read"); return result;
            }
        }
    }
    return real_audio_read(stream, buffer, frames, timeout);
}
[[maybe_unused]] void* resolve_entry(void* address) {
    HookMappings maps;
    return reinterpret_cast<void*>(resolve_hook_target(reinterpret_cast<uintptr_t>(address), maps.access()).address);
}
template<class T> bool install(void* handle, const char* symbol, T replacement, T* original) {
    if (*original || !hook_function) return false;
    void* entry = dlsym(handle, symbol);
    if (!entry) return false;
    HookMappings maps;
    HookTarget target = resolve_hook_target(reinterpret_cast<uintptr_t>(entry), maps.access());
    if (!target.address) { __android_log_print(ANDROID_LOG_WARN, tag, "native-adapter-skipped symbol=%s reason=%s", symbol, hook_target_status(target.status)); return false; }
    if (hook_function(reinterpret_cast<void*>(target.address), reinterpret_cast<void*>(replacement), reinterpret_cast<void**>(original)) == 0 && *original) {
        ++hook_count; return true;
    }
    __android_log_print(ANDROID_LOG_ERROR, tag, "native-adapter-failed symbol=%s", symbol); return false;
}
unsigned install_adapters(unsigned wanted) {
    unsigned complete = 0;
    if (wanted & sensors) {
        // Retained for hook lifetime. No hook installation in loader callbacks.
        void* handle = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (handle) {
            bool ok = install(handle, "ASensorEventQueue_enableSensor", guarded_enable, &real_enable);
            ok = install(handle, "ASensorEventQueue_registerSensor", guarded_register, &real_register) && ok;
            ok = install(handle, "ASensorEventQueue_getEvents", guarded_events, &real_events) && ok;
            ok = install(handle, "ASensorEventQueue_hasEvents", guarded_has_events, &real_has_events) && ok;
            if (ok) complete |= sensors;
        }
    }
    // Resolve helpers before publishing a read hook. Loading a library does not
    // imply its constructors or arbitrary private APIs have been intercepted.
    if (wanted & audio) {
        void* handle = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
        if (handle) {
            audio_direction = reinterpret_cast<decltype(audio_direction)>(dlsym(handle, "AAudioStream_getDirection"));
            audio_format = reinterpret_cast<decltype(audio_format)>(dlsym(handle, "AAudioStream_getFormat"));
            audio_channels = reinterpret_cast<decltype(audio_channels)>(dlsym(handle, "AAudioStream_getChannelCount"));
            audio_rate = reinterpret_cast<decltype(audio_rate)>(dlsym(handle, "AAudioStream_getSampleRate"));
            audio_state = reinterpret_cast<decltype(audio_state)>(dlsym(handle, "AAudioStream_getState"));
            if (audio_direction && audio_format && audio_channels && audio_rate && audio_state) {
                bool ok = install(handle, "AAudioStream_read", guarded_audio_read, &real_audio_read);
                ok = install(handle, "AAudioStream_requestStart", guarded_audio_start, &real_audio_start) && ok;
                if (ok) complete |= audio;
            }
        }
    }
    if (wanted & network) {
        bool ok = install(RTLD_DEFAULT, "connect", guarded_connect, &real_connect);
        ok = install(RTLD_DEFAULT, "sendto", guarded_sendto, &real_sendto) && ok;
        ok = install(RTLD_DEFAULT, "sendmsg", guarded_sendmsg, &real_sendmsg) && ok;
        ok = install(RTLD_DEFAULT, "sendmmsg", guarded_sendmmsg, &real_sendmmsg) && ok;
        if (ok) complete |= network;
    }
    __android_log_print(ANDROID_LOG_INFO, tag, "native-coverage hooks=%d requested=%u complete=%u", hook_count, wanted, complete);
    return complete;
}
}
extern "C" __attribute__((visibility("default"), used))
ModuleLoaded native_init(const NativeAPIEntries* entries) {
    if (!entries || !entries->hook_func) return nullptr;
    hook_function = entries->hook_func;
    // Deliberately no dlopen callback: it can execute under the linker lock.
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_dev_systemhelper_guard_Guard_nativeConfigure(JNIEnv*, jclass, jint soft, jint deny) {
    policy_mask.store(static_cast<uint32_t>(soft) | (uint64_t(static_cast<uint32_t>(deny)) << 32));
}
extern "C" JNIEXPORT jint JNICALL Java_dev_systemhelper_guard_Guard_nativeStart(JNIEnv*, jclass, jint soft, jint deny) {
    if (!hook_function) return -ENOSYS;
    if (started.exchange(true)) return -EALREADY;
    policy_mask.store(static_cast<uint32_t>(soft) | (uint64_t(static_cast<uint32_t>(deny)) << 32));
    return install_adapters(static_cast<unsigned>(soft | deny));
}
extern "C" JNIEXPORT jint JNICALL Java_dev_systemhelper_guard_Guard_nativeNetworkFilter(JNIEnv*, jclass) {
    return install_network_filter();
}
