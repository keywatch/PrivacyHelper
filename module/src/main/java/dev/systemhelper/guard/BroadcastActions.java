package dev.systemhelper.guard;

import android.content.IntentFilter;
import java.util.Iterator;
import java.util.Set;

/** Mutate a copy through the framework iterator, independent of its backing collection. */
final class BroadcastActions {
    static IntentFilter without(IntentFilter original, Set<String> blocked) {
        IntentFilter copy = new IntentFilter(original);
        Iterator<String> actions = copy.actionsIterator();
        if (actions != null) while (actions.hasNext()) if (blocked.contains(actions.next())) actions.remove();
        return copy;
    }
}
