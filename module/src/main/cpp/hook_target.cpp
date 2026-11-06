#include "hook_target.h"
#include <cstring>

HookTarget resolve_hook_target(uintptr_t entry, const HookMemory& memory) {
    if (!entry) return {0, HookTargetStatus::Missing};
    uintptr_t address = entry;
    for (unsigned depth = 0; depth < 4; ++depth) {
        uint32_t code[3]{};
        if ((address & 3) || !memory.read(memory.context, address, code, 4, true)) return {0, HookTargetStatus::Unreadable};
        uintptr_t pc = address;
        if (code[0] == 0xd503245f || code[0] == 0xd503201f) pc += 4;
        if (!memory.read(memory.context, pc, code, sizeof(code), true)) return {0, HookTargetStatus::Unreadable};
        if ((code[0] & 0xff000000) == 0x58000000 && (code[1] & 0xfffffc1f) == 0xd61f0000 &&
                (code[0] & 31) == ((code[1] >> 5) & 31)) return {0, HookTargetStatus::Patched};
        if ((code[0] & 0xfc000000) == 0x14000000) {
            int64_t displacement = code[0] & 0x03ffffff;
            if (displacement & (1 << 25)) displacement -= 1 << 26;
            uintptr_t destination = pc + displacement * 4;
            uintptr_t owner = memory.owner(memory.context, address);
            if (!owner || owner != memory.owner(memory.context, destination)) return {0, HookTargetStatus::ExternalBranch};
            address = destination; continue;
        }
        uint32_t page = code[0], load = code[1], branch = code[2];
        if ((page & 0x9f000000) == 0x90000000 && (load & 0xffc00000) == 0xf9400000 &&
                (branch & 0xfffffc1f) == 0xd61f0000 && (page & 31) == ((load >> 5) & 31) &&
                (load & 31) == ((branch >> 5) & 31)) {
            int64_t displacement = ((page >> 29) & 3) | (((page >> 5) & 0x7ffff) << 2);
            if (displacement & (1 << 20)) displacement -= 1 << 21;
            uintptr_t slot = (pc & ~uintptr_t(4095)) + displacement * 4096 + ((load >> 10) & 4095) * 8;
            uintptr_t owner = memory.owner(memory.context, address), destination = 0;
            if (!owner || owner != memory.owner(memory.context, slot) ||
                    !memory.read(memory.context, slot, &destination, sizeof(destination), false) || !destination ||
                    !memory.owner(memory.context, destination))
                return {0, HookTargetStatus::InvalidDispatch};
            address = destination; continue;
        }
        // A BTI-preserving absolute hook needs space after the landing pad.
        // Reject short bodies and indirect stubs instead of overwriting their neighbours.
        uint32_t body[6]{};
        if (!memory.read(memory.context, address, body, sizeof(body), true)) return {0, HookTargetStatus::Unreadable};
        for (uint32_t instruction : body) {
            if (instruction == 0 || instruction == UINT32_MAX || (instruction & 0xfffffc1f) == 0xd65f0000 || (instruction & 0xfffffc1f) == 0xd61f0000 ||
                    instruction == 0xd65f0bff || instruction == 0xd65f0fff ||
                    (instruction & 0xfc000000) == 0x14000000) return {0, HookTargetStatus::ShortBody};
        }
        if (!memory.owner(memory.context, address)) return {0, HookTargetStatus::ExternalBranch};
        return {address, HookTargetStatus::Ready};
    }
    return {0, HookTargetStatus::ChainLimit};
}
const char* hook_target_status(HookTargetStatus status) {
    switch (status) {
        case HookTargetStatus::Ready: return "ready";
        case HookTargetStatus::Missing: return "symbol-missing";
        case HookTargetStatus::Unreadable: return "unreadable-code";
        case HookTargetStatus::Patched: return "existing-inline-hook";
        case HookTargetStatus::ShortBody: return "short-or-indirect-body";
        case HookTargetStatus::ExternalBranch: return "external-branch";
        case HookTargetStatus::InvalidDispatch: return "invalid-dispatch-slot";
        case HookTargetStatus::ChainLimit: return "veneer-chain-limit";
    }
    return "unknown";
}
