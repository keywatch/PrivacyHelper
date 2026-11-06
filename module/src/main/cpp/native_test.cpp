// Exercise the real adapter functions with controlled NDK and libc backends.
// This does not assert LSPosed's inline hook engine has patched a live app.
#include "guard.cpp"
#include <cstdio>
#include <fcntl.h>

namespace {
int calls = 0;
int hook_calls = 0;
int fake_hook(void* address, void*, void** original) { ++hook_calls; *original = address; return 0; }
int fake_enable(ASensorEventQueue*, const ASensor*) { ++calls; return 17; }
ssize_t fake_events(ASensorEventQueue*, ASensorEvent* events, size_t count) {
    ++calls; if (count) memset(events, 0x5a, sizeof(ASensorEvent)); return count ? 1 : 0;
}
aaudio_direction_t direction = AAUDIO_DIRECTION_INPUT;
aaudio_direction_t fake_direction(AAudioStream*) { return direction; }
aaudio_format_t fake_format(AAudioStream*) { return AAUDIO_FORMAT_PCM_I16; }
int32_t fake_channels(AAudioStream*) { return 1; }
int32_t fake_rate(AAudioStream*) { return 48000; }
aaudio_stream_state_t fake_state(AAudioStream*) { return AAUDIO_STREAM_STATE_STARTED; }
int32_t fake_read(AAudioStream*, void*, int32_t, int64_t) { ++calls; return 19; }
aaudio_result_t fake_start(AAudioStream*) { ++calls; return 23; }
}
int main() {
    int total = 0, passed = 0;
    auto check = [&](const char* label, bool result) { ++total; if (result) ++passed; printf("%s=%s\n", label, result ? "PASS" : "FAIL"); };
    real_enable = fake_enable; real_events = fake_events;
    auto* queue = reinterpret_cast<ASensorEventQueue*>(1); auto* sensor = reinterpret_cast<ASensor*>(1);
    check("sensor_off_preserves_result", guarded_enable(queue, sensor) == 17 && calls == 1);
    policy_mask.store(sensors); check("sensor_soft_no_registration", guarded_enable(queue, sensor) == 0 && calls == 1);
    ASensorEvent event; memset(&event, 0x33, sizeof(event));
    check("sensor_soft_no_sample_copy", guarded_events(queue, &event, 1) == 0 && reinterpret_cast<unsigned char*>(&event)[0] == 0x33 && calls == 2);
    check("sensor_has_events_drains_private_queue", guarded_has_events(queue) == 0 && calls == 3);
    policy_mask.store(uint64_t(sensors) << 32); check("sensor_explicit_error", guarded_enable(queue, sensor) == -EPERM);
    policy_mask.store(0); check("sensor_off_restored", guarded_enable(queue, sensor) == 17);
    audio_direction = fake_direction; audio_format = fake_format; audio_channels = fake_channels;
    audio_rate = fake_rate; audio_state = fake_state; real_audio_read = fake_read; real_audio_start = fake_start;
    auto* stream = reinterpret_cast<AAudioStream*>(1); unsigned char pcm[34]; memset(pcm, 0x77, sizeof(pcm));
    policy_mask.store(audio); int before = calls;
    check("audio_silence_and_bounds", guarded_audio_read(stream, pcm, 16, 0) == 16 && pcm[0] == 0 && pcm[31] == 0 && pcm[32] == 0x77 && calls == before);
    direction = AAUDIO_DIRECTION_OUTPUT;
    check("audio_output_unmodified", guarded_audio_read(stream, pcm, 16, 0) == 19 && calls == before + 1);
    direction = AAUDIO_DIRECTION_INPUT; policy_mask.store(uint64_t(audio) << 32);
    check("audio_explicit_start_denied", guarded_audio_start(stream) == AAUDIO_ERROR_UNAVAILABLE);
    check("audio_explicit_read_denied", guarded_audio_read(stream, pcm, 16, 0) == AAUDIO_ERROR_UNAVAILABLE);
    policy_mask.store(0); check("audio_off_restored", guarded_audio_read(stream, pcm, 16, 0) == 19);
    real_sendto = ::sendto;
    int pair[2]; if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair)) return 2;
    int ip = socket(AF_INET, SOCK_DGRAM, 0); if (ip < 0) return 3;
    policy_mask.store(uint64_t(network) << 32);
    check("network_denies_inet", guarded_sendto(ip, "x", 1, 0, nullptr, 0) == -1 && errno == EACCES);
    char byte; check("network_retains_unix_ipc", guarded_sendto(pair[0], "x", 1, 0, nullptr, 0) == 1 && read(pair[1], &byte, 1) == 1);
    int file = open("/dev/null", O_WRONLY);
    check("network_retains_file_write", file >= 0 && write(file, "x", 1) == 1);
    policy_mask.store(0); errno = 0;
    check("network_off_restores_backend", guarded_sendto(ip, "x", 1, 0, nullptr, 0) == -1 && errno != EACCES);
    void* connect_entry = dlsym(RTLD_DEFAULT, "connect");
    auto resolved_connect = reinterpret_cast<decltype(real_connect)>(resolve_entry(connect_entry));
    check("bionic_veneer_resolved", resolved_connect && resolved_connect != reinterpret_cast<decltype(real_connect)>(connect_entry));
    errno = 0;
    check("bionic_resolved_abi", resolved_connect && resolved_connect(-1, nullptr, 0) == -1 && errno == EBADF);
    void* aaudio = dlopen("libaaudio.so", RTLD_NOW);
    void* read_entry = aaudio ? dlsym(aaudio, "AAudioStream_read") : nullptr;
    auto resolved_read = reinterpret_cast<decltype(real_audio_read)>(resolve_entry(read_entry));
    check("aaudio_veneer_resolved", resolved_read && resolved_read != reinterpret_cast<decltype(real_audio_read)>(read_entry));
    check("aaudio_resolved_abi", resolved_read && resolved_read(nullptr, nullptr, 0, 0) == AAUDIO_ERROR_NULL);
    uint32_t earlier_hook[] = {0xd503245f, 0x58000051, 0xd61f0220, 0, 0};
    check("earlier_inline_hook_not_overwritten", resolve_entry(earlier_hook) == nullptr);
    NativeAPIEntries entries{1, fake_hook, nullptr};
    check("init_has_no_loader_callback_or_hooks", native_init(&entries) == nullptr && hook_calls == 0);
    check("startup_without_caps_does_not_patch", Java_dev_systemhelper_guard_Guard_nativeStart(nullptr, nullptr, 0, 0) == 0 && hook_calls == 0);
    Java_dev_systemhelper_guard_Guard_nativeConfigure(nullptr, nullptr, sensors | audio, network);
    check("policy_update_does_not_patch_code", hook_calls == 0 && soft(sensors) && soft(audio) && deny(network));
    check("startup_never_repeated", Java_dev_systemhelper_guard_Guard_nativeStart(nullptr, nullptr, sensors, 0) == -EALREADY && hook_calls == 0);
    if (file >= 0) close(file); close(ip); close(pair[0]); close(pair[1]);
    printf("native_adapter_tests=%d passed=%d\n", total, passed); return total == passed ? 0 : 1;
}
