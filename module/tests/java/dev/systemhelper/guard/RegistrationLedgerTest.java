package dev.systemhelper.guard;

public final class RegistrationLedgerTest {
    public static void main(String[] args) {
        RegistrationLedger ledger = new RegistrationLedger(); Object receiver = new Object(), owner = new Object();
        check(!ledger.skipCleanup(owner, receiver), "unknown cleanup must reach framework");
        ledger.virtual(owner, receiver); check(ledger.skipCleanup(owner, receiver), "virtual-only cleanup must not throw");
        check(!ledger.skipCleanup(owner, receiver), "second cleanup retains framework contract");
        ledger.real(owner, receiver); ledger.virtual(owner, receiver);
        check(!ledger.skipCleanup(owner, receiver), "real then virtual must clean real listener");
        ledger.virtual(owner, receiver); ledger.real(owner, receiver);
        check(!ledger.skipCleanup(owner, receiver), "virtual then real must clean real listener");
        Object other = new Object(); ledger.virtual(owner, receiver); ledger.real(owner, other);
        check(ledger.skipCleanup(owner, receiver) && !ledger.skipCleanup(owner, other), "listeners have independent cleanup ownership");
        ledger.virtual(owner, null); check(!ledger.skipCleanup(owner, null), "sticky-query null receiver is never registered");
        ledger.virtual(owner, receiver); ledger.real(other, receiver);
        check(ledger.skipCleanup(owner, receiver) && !ledger.skipCleanup(other, receiver), "contexts have independent cleanup ownership");
        System.out.println("registration regression tests: 8 passed");
    }
    private static void check(boolean result, String name) { if (!result) throw new AssertionError(name); }
}
