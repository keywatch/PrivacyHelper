import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import org.json.JSONObject;

/** Read-only storage queries, run through app_process with a privileged caller. */
public final class StorageProbe {
    public static void main(String[] packages) throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        StorageStatsManager manager = context.getSystemService(StorageStatsManager.class);
        for (String name : packages) {
            JSONObject row = new JSONObject().put("package", name);
            try {
                StorageStats stats = manager.queryStatsForPackage(
                        StorageManager.UUID_DEFAULT, name, UserHandle.getUserHandleForUid(1000));
                row.put("app_bytes", stats.getAppBytes());
                row.put("data_bytes", stats.getDataBytes());
                row.put("cache_bytes", stats.getCacheBytes());
            } catch (Exception error) {
                row.put("error", error.toString());
            }
            System.out.println("STORAGE_PROBE " + row);
        }
        JSONObject uid = new JSONObject().put("uid", 1000);
        try {
            StorageStats stats = manager.queryStatsForUid(StorageManager.UUID_DEFAULT, 1000);
            uid.put("app_bytes", stats.getAppBytes());
            uid.put("data_bytes", stats.getDataBytes());
            uid.put("cache_bytes", stats.getCacheBytes());
        } catch (Exception error) {
            uid.put("error", error.toString());
        }
        System.out.println("STORAGE_PROBE " + uid);
        System.exit(0);
    }
}
