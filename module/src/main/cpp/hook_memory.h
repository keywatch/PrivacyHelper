#pragma once
#include "hook_target.h"
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <vector>

// Snapshot only while retained library handles keep candidate code mapped.
// No arbitrary instruction/dispatch-pointer read without a range check.
class HookMappings {
    struct Region { uintptr_t begin, end; bool executable; };
    std::vector<Region> regions;
public:
    HookMappings() {
        FILE* maps = fopen("/proc/self/maps", "re");
        if (!maps) return;
        char line[1024];
        while (fgets(line, sizeof(line), maps)) {
            unsigned long begin, end; char permission[5]{};
            if (sscanf(line, "%lx-%lx %4s", &begin, &end, permission) == 3 && permission[0] == 'r')
                regions.push_back({begin, end, permission[2] == 'x'});
        }
        fclose(maps);
    }
    static bool read(void* self, uintptr_t address, void* output, size_t count, bool code) {
        if (count > UINTPTR_MAX - address) return false;
        for (const Region& region : static_cast<HookMappings*>(self)->regions) {
            if (address >= region.begin && address + count <= region.end && (!code || region.executable)) {
                memcpy(output, reinterpret_cast<const void*>(address), count); return true;
            }
        }
        return false;
    }
    static uintptr_t owner(void*, uintptr_t address) {
        Dl_info info{};
        return dladdr(reinterpret_cast<void*>(address), &info) ? reinterpret_cast<uintptr_t>(info.dli_fbase) : 0;
    }
    HookMemory access() { return {this, read, owner}; }
};
