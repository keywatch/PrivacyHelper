package dev.systemhelper.guard;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** One policy per package; no package-specific decision logic in this model. */
public final class Policy {
    public static final int OFF = 0, SOFT = 1, DENY = 2;
    public static final String[] KEYS = {"events", "location", "identifiers", "clipboard", "records", "sensors", "audio", "screen", "network"};
    public static final String[] LABELS = {"电话与屏幕状态", "位置", "设备标识", "剪贴板", "通讯录与消息", "运动传感器", "麦克风", "截屏与录屏", "网络"};
    public static final String[] DETAILS = {
        "隐藏常见的电话、SIM 卡和屏幕状态通知。其他类型的通知不受影响。",
        "隐藏时不向应用提供位置，不会关闭手机的定位功能。应用仍可能通过其他方式推测位置。",
        "向应用提供替代标识，或拒绝读取。不会修改手机的真实设备标识。",
        "隐藏时，让应用看到空剪贴板，并暂停常见的变化通知。",
        "隐藏常见的通讯录、短信和通话记录查询结果。部分特殊读取方式可能不受限制。",
        "隐藏常见运动传感器的变化。已经开始的监听可能需要重启应用才能停止。",
        "只提供静音时，麦克风仍可能被占用。禁止录音限制更强，可能影响语音功能。部分特殊录音方式暂未覆盖。",
        "限制常见的截屏和录屏操作。部分系统级录屏方式暂未覆盖。",
        "禁止常见联网操作，应用可能显示网络错误。更多联网限制可在下方开启“加强断网”。"};
    public boolean enabled;
    public boolean kernelNetwork;
    public String seed = "";
    public final Map<String, Integer> modes = new LinkedHashMap<>();
    public Policy() { for (String key : KEYS) modes.put(key, OFF); }
    public int mode(String key) { Integer value = modes.get(key); return enabled && value != null ? value : OFF; }
    public static Policy initial() {
        Policy p = new Policy(); p.enabled = false; p.seed = java.util.UUID.randomUUID().toString();
        for (String key : new String[]{"events", "location", "identifiers", "clipboard", "records", "sensors"}) p.modes.put(key, SOFT);
        return p;
    }
    public static boolean supportsSoft(String key) { return !key.equals("screen") && !key.equals("network"); }
    public static Policy parse(String json) {
        Policy p = new Policy();
        try {
            JSONObject root = new JSONObject(json);
            p.enabled = root.optBoolean("enabled", false);
            p.kernelNetwork = root.optBoolean("kernel_network", false);
            p.seed = root.optString("seed", "");
            JSONObject settings = root.optJSONObject("modes");
            if (settings != null) for (String key : KEYS) {
                int mode = settings.optInt(key, OFF);
                if (mode < OFF || mode > DENY || (mode == SOFT && !supportsSoft(key))) mode = OFF;
                p.modes.put(key, mode);
            }
        } catch (Exception ignored) { p.enabled = false; }
        return p;
    }
    public String json() {
        JSONObject root = new JSONObject(), settings = new JSONObject();
        try {
            root.put("enabled", enabled); root.put("kernel_network", kernelNetwork);
            root.put("seed", seed);
            for (String key : KEYS) settings.put(key, modes.get(key));
            root.put("modes", settings);
        } catch (Exception error) { throw new IllegalStateException(error); }
        return root.toString();
    }
}
