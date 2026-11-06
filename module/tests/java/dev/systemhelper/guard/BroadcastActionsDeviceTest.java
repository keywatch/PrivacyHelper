package dev.systemhelper.guard;

import android.content.IntentFilter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class BroadcastActionsDeviceTest {
    public static void main(String[] args) {
        Set<String> blocked = new HashSet<>(Arrays.asList("android.intent.action.SCREEN_ON", "android.intent.action.PHONE_STATE"));
        IntentFilter original = new IntentFilter("android.intent.action.SCREEN_ON");
        original.addAction("dev.guard.KEPT"); original.addCategory("dev.guard.CATEGORY"); original.addDataScheme("content"); original.setPriority(137);
        IntentFilter filtered = BroadcastActions.without(original, blocked);
        check(filtered.countActions() == 1 && filtered.hasAction("dev.guard.KEPT"), "mixed actions filtered");
        check(original.countActions() == 2 && original.hasAction("android.intent.action.SCREEN_ON"), "original preserved");
        check(filtered.hasCategory("dev.guard.CATEGORY") && filtered.hasDataScheme("content") && filtered.getPriority() == 137, "non-action fields preserved");
        check(BroadcastActions.without(new IntentFilter("android.intent.action.PHONE_STATE"), blocked).countActions() == 0, "all-sensitive becomes empty");
        check(BroadcastActions.without(new IntentFilter(), blocked).countActions() == 0, "empty filter supported");
        check(BroadcastActions.without(filtered, blocked).countActions() == 1, "repeat filter unchanged");
        System.out.println("device broadcast regression: 6 passed");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
