package dev.systemhelper.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import io.github.libxposed.service.XposedService;

public final class SettingsActivity extends Activity {
    private static final int INK = 0xff182231, MUTED = 0xff617080, BLUE = 0xff246bdf, BACKGROUND = 0xfff3f5f8;
    private SharedPreferences preferences;
    private String selected;
    private boolean changeNoticeShown;
    private final Runnable serviceChanged = this::connect;
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Log.i("SystemHelperGuard", "manager-create version=2.3");
        setTitle("隐私保护");
        GuardApplication.changed = serviceChanged;
        connect();
    }
    private void connect() {
        preferences = GuardApplication.preferences;
        if (preferences == null) {
            selected = null;
            LinearLayout body = page("隐私保护", "还差一步", false);
            paragraph(body, "请在 LSPosed 中开启“隐私保护”，然后回到这里。", MUTED);
            return;
        }
        if (!preferences.contains("initialized")) {
            boolean saved = preferences.edit().putBoolean("initialized", true)
                .putString("app:com.qualcomm.qti.services.systemhelper", Policy.initial().json())
                .putString("app:com.mobiletools.systemhelper", Policy.initial().json()).commit();
            if (!saved) { paragraph(page("暂时无法保存", "请稍后再打开试试。", false), "原有设置未改变。", MUTED); return; }
        }
        if (selected == null) showPackages(); else showPolicy(selected);
    }
    private LinearLayout column() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); return box; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private void paragraph(LinearLayout parent, String value, int color) {
        TextView view = text(value, 14, color); view.setPadding(0, dp(8), 0, dp(12)); parent.addView(view);
    }
    private GradientDrawable shape(int color) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(16)); return background;
    }
    private LinearLayout card(LinearLayout body) {
        LinearLayout card = column(); card.setPadding(dp(16), dp(12), dp(16), dp(12)); card.setBackground(shape(Color.WHITE));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.bottomMargin = dp(12); body.addView(card, layout); return card;
    }
    private LinearLayout page(String title, String subtitle, boolean back) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BACKGROUND);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            android.view.WindowInsetsController controller = view.getWindowInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            return insets;
        });
        LinearLayout body = column(); body.setPadding(dp(20), dp(16), dp(20), dp(28)); scroll.addView(body); setContentView(scroll); scroll.requestApplyInsets();
        if (back) {
            TextView previous = text("‹  应用列表", 16, BLUE); previous.setPadding(0, 0, 0, dp(20)); previous.setOnClickListener(view -> showPackages()); body.addView(previous);
        }
        TextView heading = text(title, 29, INK); heading.setTypeface(null, Typeface.BOLD); body.addView(heading);
        paragraph(body, subtitle, MUTED); return body;
    }
    private Button button(LinearLayout body, String label, boolean primary, View.OnClickListener action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(16);
        button.setTextColor(primary ? Color.WHITE : BLUE); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? BLUE : 0xffe5edfb));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(52)); layout.topMargin = dp(8); body.addView(button, layout); button.setOnClickListener(action); return button;
    }
    private String label(String name) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(name, 0)).toString(); }
        catch (PackageManager.NameNotFoundException missing) { return name; }
    }
    private void icon(LinearLayout row, String name) {
        ImageView icon = new ImageView(this);
        try { icon.setImageDrawable(getPackageManager().getApplicationIcon(name)); }
        catch (PackageManager.NameNotFoundException missing) { icon.setImageResource(android.R.drawable.sym_def_app_icon); }
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(44), dp(44)); size.rightMargin = dp(14); row.addView(icon, size);
    }
    private void showPackages() {
        selected = null; changeNoticeShown = false;
        LinearLayout body = page("隐私保护", "选择应用，决定它能看到哪些信息。", false);
        ArrayList<String> packages = new ArrayList<>();
        for (String key : preferences.getAll().keySet()) if (key.startsWith("app:")) packages.add(key.substring(4));
        packages.sort((a, b) -> java.text.Collator.getInstance(Locale.CHINA).compare(label(a), label(b)));
        List<String> scoped = GuardApplication.service.getScope();
        int count = 0;
        for (String name : packages) {
            Policy policy = Policy.parse(preferences.getString("app:" + name, "{}"));
            if (policy.enabled && scoped.contains(name)) ++count;
            LinearLayout outer = card(body), row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); outer.addView(row); icon(row, name);
            LinearLayout description = column(); row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
            TextView title = text(label(name), 18, INK); title.setTypeface(null, Typeface.BOLD); description.addView(title);
            String status = !policy.enabled ? "未开启" : scoped.contains(name) ? "保护已开启" : "还需完成启用";
            description.addView(text(status, 14, policy.enabled && scoped.contains(name) ? BLUE : MUTED));
            row.addView(text("›", 28, MUTED)); outer.setContentDescription(label(name) + "，" + status); outer.setOnClickListener(view -> showPolicy(name));
        }
        paragraph(body, "已为 " + count + " 个应用开启保护", MUTED);
        button(body, "＋ 添加应用", true, view -> addPackage());
        button(body, "了解保护方式", false, view -> new AlertDialog.Builder(this).setTitle("如何选择")
            .setMessage("隐藏信息：让应用看到空内容或替代信息，通常更少影响使用。\n\n禁止访问：直接阻止操作，可能影响应用功能。\n\n保护覆盖常见的读取方式，部分特殊收集方式仍可能绕过。")
            .setPositiveButton("知道了", null).show());
    }
    private boolean selectable(String name) {
        return !name.equals(getPackageName()) && !name.equals("android") && !name.equals("com.android.systemui") && !name.equals("com.android.shell");
    }
    private static final class AppChoice {
        final String name, title;
        final boolean system;
        AppChoice(String name, String title, boolean system) { this.name = name; this.title = title; this.system = system; }
    }
    private void addPackage() {
        ArrayList<AppChoice> all = new ArrayList<>();
        for (ApplicationInfo app : getPackageManager().getInstalledApplications(0)) if (selectable(app.packageName)) all.add(new AppChoice(app.packageName, app.loadLabel(getPackageManager()).toString(), (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0));
        java.text.Collator order = java.text.Collator.getInstance(Locale.CHINA); all.sort((a, b) -> order.compare(a.title, b.title));
        ArrayList<AppChoice> visible = new ArrayList<>(all);
        LinearLayout content = column(); content.setPadding(dp(16), dp(8), dp(16), 0);
        EditText search = new EditText(this); search.setSingleLine(); search.setHint("搜索应用名称"); content.addView(search);
        ListView list = new ListView(this); content.addView(list, new LinearLayout.LayoutParams(-1, dp(380)));
        ArrayAdapter<AppChoice> adapter = new ArrayAdapter<AppChoice>(this, android.R.layout.simple_list_item_2, android.R.id.text1, visible) {
            @Override public View getView(int position, View convert, ViewGroup parent) {
                View row = super.getView(position, convert, parent); AppChoice app = getItem(position);
                ((TextView) row.findViewById(android.R.id.text1)).setText(app.title);
                ((TextView) row.findViewById(android.R.id.text2)).setText(preferences.contains("app:" + app.name) ? "已添加" : app.system ? "系统应用" : "已安装"); return row;
            }
        };
        list.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选择应用").setView(content).setNegativeButton("取消", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            String name = visible.get(position).name;
            if (!preferences.contains("app:" + name) && !preferences.edit().putString("app:" + name, Policy.initial().json()).commit()) { toast("未能添加，请再试一次"); return; }
            dialog.dismiss(); showPolicy(name);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT); visible.clear();
                for (AppChoice app : all) if (app.title.toLowerCase(Locale.ROOT).contains(query) || app.name.toLowerCase(Locale.ROOT).contains(query)) visible.add(app);
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        dialog.show();
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void enableWithScope(String name, Policy policy, Switch enabled) {
        enabled.setEnabled(false);
        GuardApplication.service.requestScope(Collections.singletonList(name), new XposedService.OnScopeEventListener() {
            @Override public void onScopeRequestApproved(List<String> approved) { runOnUiThread(() -> {
                if (approved.contains(name)) { policy.enabled = true; save(name, policy); }
                if (!isFinishing() && !isDestroyed()) showPolicy(name);
            }); }
            @Override public void onScopeRequestFailed(String message) { runOnUiThread(() -> {
                Log.w("SystemHelperGuard", "scope-request-failed package=" + name);
                toast("未能启用，请在 LSPosed 中选中这个应用");
                if (!isFinishing() && !isDestroyed()) showPolicy(name);
            }); }
        });
    }
    private boolean save(String name, Policy policy) {
        if (policy.seed.isEmpty()) policy.seed = java.util.UUID.randomUUID().toString();
        try {
            if (preferences.edit().putString("app:" + name, policy.json()).commit()) {
                Log.i("SystemHelperGuard", "settings-saved package=" + name + " enabled=" + policy.enabled);
                if (!changeNoticeShown) { toast("已保存，部分设置需重启应用生效"); changeNoticeShown = true; }
                return true;
            }
        } catch (RuntimeException error) { Log.e("SystemHelperGuard", "settings-save-failed package=" + name, error); }
        toast("未能保存，请稍后再试"); return false;
    }
    private void section(LinearLayout body, String title) { TextView text = text(title, 15, MUTED); text.setPadding(dp(4), dp(14), 0, dp(10)); body.addView(text); }
    private String[] options(String key) {
        switch (key) {
            case "location": return new String[]{"允许定位", "隐藏位置", "禁止定位"};
            case "identifiers": return new String[]{"允许读取", "替代标识", "禁止读取"};
            case "clipboard": return new String[]{"允许读取", "显示为空", "禁止读取"};
            case "events": return new String[]{"正常通知", "隐藏变化", "禁止监听"};
            case "sensors": return new String[]{"允许读取", "隐藏变化", "禁止读取"};
            case "audio": return new String[]{"允许录音", "只提供静音", "禁止录音"};
            case "screen": return new String[]{"允许录屏", "禁止录屏"};
            case "network": return new String[]{"允许联网", "禁止联网"};
            default: return new String[]{"允许读取", "隐藏内容", "禁止读取"};
        }
    }
    private void setting(LinearLayout box, String name, Policy policy, String key) {
        int index = java.util.Arrays.asList(Policy.KEYS).indexOf(key);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(64)); box.addView(row);
        TextView title = text(Policy.LABELS[index] + "  ⓘ", 16, INK); row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        title.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(Policy.LABELS[index]).setMessage(Policy.DETAILS[index]).setPositiveButton("知道了", null).show());
        Spinner choice = new Spinner(this); choice.setContentDescription(Policy.LABELS[index]);
        choice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options(key)));
        int value = policy.modes.get(key); choice.setSelection(Policy.supportsSoft(key) ? value : value == Policy.DENY ? 1 : 0);
        row.addView(choice, new LinearLayout.LayoutParams(dp(143), -2));
        choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int mode = !Policy.supportsSoft(key) && position == 1 ? Policy.DENY : position;
                int previous = policy.modes.get(key); if (previous == mode) return;
                policy.modes.put(key, mode);
                if (!save(name, policy)) { policy.modes.put(key, previous); choice.setSelection(Policy.supportsSoft(key) ? previous : previous == Policy.DENY ? 1 : 0); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    private void showPolicy(String name) {
        selected = name; changeNoticeShown = false;
        Policy policy = Policy.parse(preferences.getString("app:" + name, "{}"));
        LinearLayout body = page(label(name), "更改会自动保存。部分设置需重启应用生效。", true);
        LinearLayout main = card(body);
        Switch enabled = new Switch(this); enabled.setText("开启保护"); enabled.setTextSize(20); enabled.setTextColor(INK); enabled.setChecked(policy.enabled); main.addView(enabled);
        boolean scoped = GuardApplication.service.getScope().contains(name);
        if (!scoped) {
            paragraph(main, "还需在 LSPosed 中允许保护这个应用。", MUTED);
            button(main, "完成启用", true, view -> enableWithScope(name, policy, enabled));
        }
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !GuardApplication.service.getScope().contains(name)) {
                enableWithScope(name, policy, enabled); return;
            }
            boolean previous = policy.enabled; policy.enabled = checked;
            if (!save(name, policy)) { policy.enabled = previous; showPolicy(name); }
        });
        section(body, "个人信息"); LinearLayout personal = card(body);
        for (String key : new String[]{"location", "identifiers", "records", "clipboard"}) setting(personal, name, policy, key);
        section(body, "设备与活动"); LinearLayout device = card(body);
        for (String key : new String[]{"events", "sensors", "audio", "screen"}) setting(device, name, policy, key);
        section(body, "联网"); setting(card(body), name, policy, "network");
        TextView advanced = text("更多设置  ›", 16, BLUE); advanced.setPadding(dp(4), dp(14), 0, dp(14)); body.addView(advanced);
        LinearLayout extra = card(body); extra.setVisibility(View.GONE);
        advanced.setOnClickListener(view -> { boolean open = extra.getVisibility() == View.GONE; extra.setVisibility(open ? View.VISIBLE : View.GONE); advanced.setText(open ? "收起更多设置  ⌃" : "更多设置  ›"); });
        CheckBox kernel = new CheckBox(this); kernel.setText("加强断网"); kernel.setTextSize(17); kernel.setChecked(policy.kernelNetwork); extra.addView(kernel);
        paragraph(extra, "限制更多联网方式。开启或关闭后都需重启应用；可能影响使用，也无法阻止其他应用代为联网。", MUTED);
        kernel.setOnCheckedChangeListener((button, checked) -> { boolean previous = policy.kernelNetwork; if (previous == checked) return; policy.kernelNetwork = checked; if (!save(name, policy)) { policy.kernelNetwork = previous; kernel.setChecked(previous); } });
        button(extra, "应用信息", false, view -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + name))));
        paragraph(extra, name, MUTED);
        button(extra, "从列表移除", false, view -> new AlertDialog.Builder(this).setTitle("移除这个应用？").setMessage("将停止对它应用这些保护设置。")
            .setNegativeButton("取消", null).setPositiveButton("移除", (dialog, which) -> { if (preferences.edit().remove("app:" + name).commit()) showPackages(); else toast("未能移除，请稍后再试"); }).show());
    }
    @Override public void onBackPressed() { if (selected != null && preferences != null) showPackages(); else super.onBackPressed(); }
    @Override protected void onResume() { super.onResume(); Log.i("SystemHelperGuard", "manager-resumed framework_connected=" + (GuardApplication.preferences != null)); }
    @Override protected void onDestroy() { if (GuardApplication.changed == serviceChanged) GuardApplication.changed = null; super.onDestroy(); }
}
