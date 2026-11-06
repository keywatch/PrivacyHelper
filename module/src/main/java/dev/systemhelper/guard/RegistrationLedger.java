package dev.systemhelper.guard;

import java.util.WeakHashMap;

/** Records cleanup ownership, not copies of framework listener state. */
final class RegistrationLedger {
    private static final int REAL = 1, VIRTUAL = 2;
    private final WeakHashMap<Object, WeakHashMap<Object, Integer>> owners = new WeakHashMap<>();
    synchronized void real(Object owner, Object listener) { record(owner, listener, REAL); }
    synchronized void virtual(Object owner, Object listener) { record(owner, listener, VIRTUAL); }
    private void record(Object owner, Object listener, int kind) {
        if (owner == null || listener == null) return;
        WeakHashMap<Object, Integer> registrations = owners.computeIfAbsent(owner, ignored -> new WeakHashMap<>());
        registrations.put(listener, registrations.getOrDefault(listener, 0) | kind);
    }
    synchronized boolean skipCleanup(Object owner, Object listener) {
        WeakHashMap<Object, Integer> registrations = owners.get(owner);
        if (registrations == null) return false;
        Integer kind = registrations.remove(listener);
        if (registrations.isEmpty()) owners.remove(owner);
        // Any real registration keeps ownership with the framework, even if
        // a subsequent policy change also created a virtual registration.
        return kind != null && kind == VIRTUAL;
    }
}
