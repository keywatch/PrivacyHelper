#include "hook_target.h"
#include "hook_memory.h"
#include <cassert>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>

struct Memory {
    struct Segment { uintptr_t start, owner; bool executable; std::vector<unsigned char> bytes; };
    std::vector<Segment> segments{{0x1000, 1, true, std::vector<unsigned char>(4096)}, {0x2000, 1, false, std::vector<unsigned char>(4096)}};
    Memory() { for (uintptr_t pc = 0x1000; pc < 0x2000; pc += 4) put(pc, uint32_t(0xd503201f)); }
    template<class T> void put(uintptr_t at, T value) {
        for (Segment& s : segments) if (at >= s.start && at + sizeof(value) <= s.start + s.bytes.size()) {
            memcpy(s.bytes.data() + at - s.start, &value, sizeof(value)); return;
        }
        assert(false);
    }
    static bool read(void* context, uintptr_t at, void* output, size_t count, bool code) {
        if (count > UINTPTR_MAX - at) return false;
        for (Segment& s : static_cast<Memory*>(context)->segments)
            if (at >= s.start && at + count <= s.start + s.bytes.size() && (!code || s.executable)) {
                memcpy(output, s.bytes.data() + at - s.start, count); return true;
            }
        return false;
    }
    static uintptr_t owner(void* context, uintptr_t at) {
        for (Segment& s : static_cast<Memory*>(context)->segments) if (at >= s.start && at - s.start < s.bytes.size()) return s.owner;
        return 0;
    }
    HookTarget resolve(uintptr_t at = 0x1000) { return resolve_hook_target(at, {this, read, owner}); }
};

int main() {
    int passed = 0;
    auto check = [&](bool result, const char* name) { if (!result) { fprintf(stderr, "FAIL %s\n", name); std::abort(); } ++passed; };
    { Memory m; check(m.resolve().address == 0x1000, "normal body accepted"); }
    { Memory m; check(m.resolve(0).status == HookTargetStatus::Missing, "missing symbol rejected"); }
    { Memory m; check(m.resolve(0x9000).status == HookTargetStatus::Unreadable, "unmapped pointer rejected"); }
    { Memory m; check(m.resolve(0x1001).status == HookTargetStatus::Unreadable, "unaligned instruction rejected"); }
    { Memory m; check(m.resolve(0x2000).status == HookTargetStatus::Unreadable, "non-executable mapping rejected"); }
    { Memory m; m.segments[0].owner = 0; check(m.resolve().status == HookTargetStatus::ExternalBranch, "anonymous implementation rejected"); }
    { Memory m; check(m.resolve(UINTPTR_MAX - 3).status == HookTargetStatus::Unreadable, "range overflow rejected"); }
    { Memory m; check(m.resolve(0x1ff8).status == HookTargetStatus::Unreadable, "page boundary is bounded"); }
    { Memory m; m.put(0x1000, uint32_t(0xd503245f)); m.put(0x1004, uint32_t(0x58000051)); m.put(0x1008, uint32_t(0xd61f0220)); check(m.resolve().status == HookTargetStatus::Patched, "existing hook rejected"); }
    { Memory m; m.put(0x1000, uint32_t(0xd503245f)); m.put(0x1004, uint32_t(0x1400003f)); check(m.resolve().address == 0x1100, "8-byte BTI branch resolves to body"); }
    { Memory m; m.put(0x1100, uint32_t(0x17ffffc0)); check(m.resolve(0x1100).address == 0x1000, "negative branch displacement"); }
    { Memory m; m.put(0x1000, uint32_t(0x14000000)); check(m.resolve().status == HookTargetStatus::ChainLimit, "cyclic veneer never patches"); }
    { Memory m; m.put(0x1000, uint32_t(0xd65f03c0)); check(m.resolve().status == HookTargetStatus::ShortBody, "short return body rejected"); }
    { Memory m; m.put(0x1010, uint32_t(0xd61f0200)); check(m.resolve().status == HookTargetStatus::ShortBody, "unrecognized indirect stub rejected"); }
    { Memory m; m.put(0x1000, uint32_t(0x14000800)); check(m.resolve().status == HookTargetStatus::ExternalBranch, "branch to another owner rejected"); }
    auto dispatch = [](Memory& m) {
        m.put(0x1000, uint32_t(0xd503245f)); m.put(0x1004, uint32_t(0xb0000008));
        m.put(0x1008, uint32_t(0xf9400510)); m.put(0x100c, uint32_t(0xd61f0200));
    };
    { Memory m; dispatch(m); m.put(0x2008, uintptr_t(0x1100)); check(m.resolve().address == 0x1100, "16-byte dispatch resolves without changing veneer"); }
    { Memory m; dispatch(m); check(m.resolve().status == HookTargetStatus::InvalidDispatch, "null dispatch rejected"); }
    { Memory m; dispatch(m); m.segments[1].owner = 2; m.put(0x2008, uintptr_t(0x1100)); check(m.resolve().status == HookTargetStatus::InvalidDispatch, "dispatch outside owning object rejected"); }
    { Memory m; dispatch(m); m.put(0x2008, uintptr_t(0x9000)); check(m.resolve().status == HookTargetStatus::InvalidDispatch, "unmapped dispatch destination rejected"); }
    // Exercise the production /proc/self/maps reader against real guard pages.
    size_t page = sysconf(_SC_PAGESIZE);
    auto* mapped = static_cast<unsigned char*>(mmap(nullptr, page * 3, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    check(mapped != MAP_FAILED, "allocate guarded fixture");
    check(mprotect(mapped, page, PROT_NONE) == 0 && mprotect(mapped + page, page, PROT_READ) == 0 &&
        mprotect(mapped + page * 2, page, PROT_READ | PROT_EXEC) == 0, "protect guarded fixture");
    HookMappings mappings; auto access = mappings.access(); uint32_t instruction;
    check(!access.read(access.context, reinterpret_cast<uintptr_t>(mapped), &instruction, 4, false), "real inaccessible page not dereferenced");
    check(!access.read(access.context, reinterpret_cast<uintptr_t>(mapped + page), &instruction, 4, true), "real non-code page rejected");
    check(access.read(access.context, reinterpret_cast<uintptr_t>(mapped + page * 2), &instruction, 4, true), "real executable range readable");
    check(!access.read(access.context, reinterpret_cast<uintptr_t>(mapped + page - 2), &instruction, 4, false), "real boundary crossing rejected");
    check(!access.read(access.context, UINTPTR_MAX - 1, &instruction, 4, false), "production reader bounds overflow");
    munmap(mapped, page * 3);
    printf("native target regression tests: %d passed\n", passed);
}
