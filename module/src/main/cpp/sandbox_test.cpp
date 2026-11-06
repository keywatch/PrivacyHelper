#include "sandbox.h"
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <initializer_list>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

std::atomic<bool> gate{false};
std::atomic<bool> worker_blocked{false};
void* worker(void*) {
    while (!gate.load()) usleep(1000);
    errno = 0;
    int fd = syscall(__NR_socket, AF_INET, SOCK_STREAM, 0);
    worker_blocked.store(fd == -1 && errno == EPERM);
    if (fd >= 0) close(fd);
    return nullptr;
}
int main() {
    int existing = socket(AF_INET, SOCK_DGRAM, 0);
    int pair[2]; if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) return 2;
    pthread_t thread; if (pthread_create(&thread, nullptr, worker, nullptr)) return 3;
    int status = install_network_filter();
    if (status) { printf("install_failed=%d\n", status); gate.store(true); pthread_join(thread, nullptr); return 4; }
    int total = 0, passed = 0;
    auto check = [&](const char* name, bool result) { ++total; if (result) ++passed; printf("%s=%s\n", name, result ? "PASS" : "FAIL"); };
    for (int family : {AF_INET, AF_INET6, AF_PACKET}) {
        errno = 0; long result = syscall(__NR_socket, family, SOCK_DGRAM, 0);
        check("direct_syscall_socket_denied", result == -1 && errno == EPERM);
    }
    errno = 0; check("io_uring_denied", syscall(__NR_io_uring_setup, 0, nullptr) == -1 && errno == EPERM);
    int file = syscall(__NR_openat, AT_FDCWD, "/proc/self/stat", O_RDONLY, 0);
    char bytes[128]; check("art_proc_stat_readable", file >= 0 && read(file, bytes, sizeof(bytes)) > 0);
    if (file >= 0) close(file);
    file = open("/apex/com.android.art/javalib/core-oj.jar", O_RDONLY); check("ordinary_file_readable", file >= 0 && read(file, bytes, 1) == 1);
    void* page = file >= 0 ? mmap(nullptr, 4096, PROT_READ, MAP_PRIVATE, file, 0) : MAP_FAILED;
    check("file_mmap_allowed", page != MAP_FAILED); if (page != MAP_FAILED) munmap(page, 4096); if (file >= 0) close(file);
    check("unix_ipc_works", write(pair[0], "x", 1) == 1 && read(pair[1], bytes, 1) == 1);
    int local = socket(AF_UNIX, SOCK_DGRAM, 0); check("new_unix_socket_allowed", local >= 0); if (local >= 0) close(local);
    int domain = 0; socklen_t size = sizeof(domain);
    check("existing_socket_scope_boundary", existing >= 0 && getsockopt(existing, SOL_SOCKET, SO_DOMAIN, &domain, &size) == 0 && domain == AF_INET);
    gate.store(true); pthread_join(thread, nullptr); check("tsync_existing_worker", worker_blocked.load());
    close(pair[0]); close(pair[1]); if (existing >= 0) close(existing);
    printf("kernel_network_tests=%d passed=%d\n", total, passed); return total == passed ? 0 : 1;
}
