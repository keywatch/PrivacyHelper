#pragma once
#include <cstddef>
#include <cstdint>

enum class HookTargetStatus { Ready, Missing, Unreadable, Patched, ShortBody, ExternalBranch, InvalidDispatch, ChainLimit };
struct HookMemory {
    void* context;
    // read must reject unmapped ranges, overflow and non-executable code.
    bool (*read)(void*, uintptr_t, void*, size_t, bool code);
    uintptr_t (*owner)(void*, uintptr_t);
};
struct HookTarget { uintptr_t address; HookTargetStatus status; };
HookTarget resolve_hook_target(uintptr_t entry, const HookMemory& memory);
const char* hook_target_status(HookTargetStatus status);
