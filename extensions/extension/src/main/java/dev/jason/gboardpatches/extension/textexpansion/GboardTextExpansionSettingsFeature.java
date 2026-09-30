package dev.jason.gboardpatches.extension.textexpansion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.jason.gboardpatches.extension.settings.GboardPatchesFeatureAvailability;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract;

public final class GboardTextExpansionSettingsFeature
        implements GboardPatchesSettingsContract.Feature {
    private static final String TAG = "GboardTextExpansion";
    private static final String EXPORT_FILE_NAME = "gboard-text-expansion.json";
    private static final String EXPORT_MIME_TYPE = "application/json";
    private static final String[] IMPORT_MIME_TYPES = {
            "application/json", "text/json", "text/plain"
    };

    public GboardTextExpansionSettingsFeature(Context context) {
    }

    @Override
    public String getEntryTitle() {
        return "快捷文本";
    }

    @Override
    public String getEntrySummary() {
        return "用快捷码快速输入电话号码、邮箱、地址、网址或常用句子。";
    }

    @Override
    public boolean isAvailable(Context context) {
        return GboardPatchesFeatureAvailability.hasFeature(
                context,
                GboardPatchesFeatureAvailability.FEATURE_LONG_PRESS_QUICK_ACTIONS);
    }

    @Override
    public GboardPatchesSettingsContract.Screen buildScreen(
            GboardPatchesSettingsContract.FeatureHost host) {
        Context context = host == null ? null : host.getContext();
        if (context == null) {
            return errorScreen();
        }
        try {
            GboardTextExpansionSettings.ensureDefaults(context);
            SharedPreferences preferences = GboardTextExpansionSettings.preferences(context);
            boolean enabled = GboardTextExpansionSettings.readEnabled(preferences);
            boolean keepTrigger = GboardTextExpansionSettings.readKeepTrigger(preferences);
            List<GboardTextExpansionSettings.Entry> entries =
                    GboardTextExpansionSettings.readEntries(preferences);

            List<GboardPatchesSettingsContract.Row> behavior = new ArrayList<>();
            behavior.add(new GboardPatchesSettingsContract.ToggleRow(
                    "启用快捷文本",
                    "输入快捷码后按空格、回车或常用标点即可展开。密码输入框会自动禁用。",
                    true,
                    enabled,
                    value -> GboardTextExpansionSettings.writeEnabled(context, value)));
            behavior.add(new GboardPatchesSettingsContract.ToggleRow(
                    "保留触发符",
                    "开启后，sjh + 空格会展开为“手机号 + 空格”；关闭后只插入展开文本。",
                    enabled,
                    keepTrigger,
                    value -> GboardTextExpansionSettings.writeKeepTrigger(context, value)));

            List<GboardPatchesSettingsContract.Row> rules = new ArrayList<>();
            rules.add(new GboardPatchesSettingsContract.CommandRow(
                    "编辑快捷文本",
                    "每行一条：快捷码=展开文本。例如：sjh=13800138000",
                    enabled,
                    new EditRulesAction(host, entries)));
            rules.add(new GboardPatchesSettingsContract.InfoRow(
                    "当前规则",
                    entries.size() + " / " + GboardTextExpansionSettings.MAX_ENTRIES,
                    true));
            int shown = Math.min(entries.size(), 20);
            for (int index = 0; index < shown; index++) {
                GboardTextExpansionSettings.Entry entry = entries.get(index);
                rules.add(new GboardPatchesSettingsContract.InfoRow(
                        entry.shortcut,
                        preview(entry.text),
                        true));
            }
            if (entries.size() > shown) {
                rules.add(new GboardPatchesSettingsContract.InfoRow(
                        "……",
                        "另有 " + (entries.size() - shown) + " 条规则未在此列表显示。",
                        true));
            }

            List<GboardPatchesSettingsContract.Row> transfer = Arrays.asList(
                    new GboardPatchesSettingsContract.CommandRow(
                            "导出 JSON",
                            "导出快捷文本规则和开关设置，方便备份或分享。",
                            true,
                            () -> exportRules(host)),
                    new GboardPatchesSettingsContract.CommandRow(
                            "导入 JSON",
                            "从快捷文本 JSON 文件恢复配置。",
                            true,
                            () -> importRules(host)));

            List<GboardPatchesSettingsContract.Row> advanced = Collections.singletonList(
                    new GboardPatchesSettingsContract.DangerRow(
                            "清空全部规则",
                            "删除所有快捷文本映射，不会影响其他 Gboard 设置。",
                            !entries.isEmpty(),
                            () -> {
                                GboardTextExpansionSettings.writeEntries(
                                        context, Collections.emptyList());
                                GboardPatchesSettingsContract.refresh(host);
                            },
                            "清空快捷文本？",
                            "此操作会删除全部快捷文本映射。"));

            return new GboardPatchesSettingsContract.Screen(
                    getEntryTitle(),
                    "Gboard",
                    getEntryTitle(),
                    "支持数字、字母、邮箱、电话号码、网址、地址和常用句子。\n"
                            + "中文输入时会按实际输入的字母匹配，不受第一候选词影响。",
                    Collections.emptyList(),
                    Arrays.asList(
                            new GboardPatchesSettingsContract.Section("功能", behavior),
                            new GboardPatchesSettingsContract.Section("快捷规则", rules),
                            new GboardPatchesSettingsContract.Section("导入与导出", transfer),
                            new GboardPatchesSettingsContract.Section(
                                    "高级",
                                    null,
                                    GboardPatchesSettingsContract.SectionStyle.ADVANCED,
                                    advanced)),
                    GboardPatchesSettingsContract.RefreshPolicy.none(),
                    GboardPatchesSettingsContract.PanelStyle.FLAT);
        } catch (Throwable throwable) {
            Log.w(TAG, "Failed to build Text Expansion settings", throwable);
            return errorScreen();
        }
    }

    private static final class EditRulesAction implements Runnable {
        private final GboardPatchesSettingsContract.FeatureHost host;
        private final List<GboardTextExpansionSettings.Entry> entries;

        EditRulesAction(GboardPatchesSettingsContract.FeatureHost host,
                List<GboardTextExpansionSettings.Entry> entries) {
            this.host = host;
            this.entries = entries;
        }

        @Override
        public void run() {
            if (host == null || !(host.getContext() instanceof Activity activity)
                    || activity.isFinishing()) {
                return;
            }
            GboardPatchesSettingsContract.showManagedDialog(host, onDismiss ->
                    showEditorDialog(activity, host, entries, onDismiss));
        }
    }

    private static boolean showEditorDialog(
            Activity activity,
            GboardPatchesSettingsContract.FeatureHost host,
            List<GboardTextExpansionSettings.Entry> entries,
            Runnable onDismiss) {
        EditText editor = new EditText(activity);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setMinLines(8);
        editor.setMaxLines(18);
        editor.setHorizontallyScrolling(false);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setText(GboardTextExpansionSettings.toEditorText(entries));
        editor.setSelection(editor.length());
        editor.setHint("sjh=13800138000\nyx=name@example.com\ndz=辽宁省大连市……");

        TextView help = new TextView(activity);
        help.setText("格式：快捷码=展开文本，每行一条。展开文本可使用 \\n、\\t。");
        help.setPadding(dp(activity, 20), dp(activity, 10), dp(activity, 20), dp(activity, 8));

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(help, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(editor, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("编辑快捷文本")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            if (onDismiss != null) {
                onDismiss.run();
            }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        List<GboardTextExpansionSettings.Entry> parsed =
                                GboardTextExpansionSettings.parseEditorText(
                                        editor.getText().toString());
                        if (!GboardTextExpansionSettings.writeEntries(
                                activity, parsed)) {
                            throw new IllegalStateException("保存失败");
                        }
                        dialog.dismiss();
                        GboardPatchesSettingsContract.refresh(host);
                    } catch (Throwable failure) {
                        editor.setError(failure.getMessage());
                    }
                }));
        dialog.show();
        return true;
    }

    private static void exportRules(GboardPatchesSettingsContract.FeatureHost host) {
        if (host == null || host.getContext() == null) {
            return;
        }
        try {
            String json = GboardTextExpansionSettings.exportJson(host.getContext());
            GboardPatchesSettingsContract.createTextDocument(
                    host,
                    EXPORT_FILE_NAME,
                    EXPORT_MIME_TYPE,
                    json,
                    () -> GboardPatchesSettingsContract.showMessage(
                            host, "快捷文本已导出"));
        } catch (Throwable failure) {
            GboardPatchesSettingsContract.showMessage(
                    host, "导出失败：" + failure.getMessage());
        }
    }

    private static void importRules(GboardPatchesSettingsContract.FeatureHost host) {
        if (host == null || host.getContext() == null) {
            return;
        }
        GboardPatchesSettingsContract.openTextDocument(
                host,
                IMPORT_MIME_TYPES,
                text -> {
                    try {
                        GboardTextExpansionSettings.importJson(host.getContext(), text);
                        GboardPatchesSettingsContract.showMessage(
                                host, "快捷文本已导入");
                        GboardPatchesSettingsContract.refresh(host);
                    } catch (Throwable failure) {
                        GboardPatchesSettingsContract.showMessage(
                                host, "导入失败：" + failure.getMessage());
                    }
                });
    }

    private static String preview(String text) {
        String value = text == null ? "" : text.replace("\n", " ↵ ");
        return value.length() <= 80 ? value : value.substring(0, 77) + "…";
    }

    private static int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private GboardPatchesSettingsContract.Screen errorScreen() {
        return new GboardPatchesSettingsContract.Screen(
                getEntryTitle(),
                "Gboard",
                getEntryTitle(),
                "",
                Collections.singletonList(new GboardPatchesSettingsContract.StatusBlock(
                        "快捷文本暂不可用",
                        "请重新打开 Gboard 设置后再试。",
                        GboardPatchesSettingsContract.StatusTone.WARNING)),
                Collections.emptyList());
    }
}
