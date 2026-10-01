from pathlib import Path
from datetime import datetime, timezone
import json

VERSION = "3.11.0-enhanced.2-dev.14"
REPO = "DWcrea/Gboard-patches-Enhanced"
TAG = "v" + VERSION

undo_path = Path("extensions/extension/src/main/java/dev/jason/gboardpatches/extension/longpressquickactions/GboardBackspaceSwipeUndoRuntime.java")
undo = undo_path.read_text(encoding="utf-8")

old = "    private static final long TRAILING_REPAIR_DELAY_MS = 120L;\n"
new = (
    "    private static final long TRAILING_REPAIR_DELAY_MS = 120L;\n"
    "    private static final long EMPTY_FIELD_RETRY_DELAY_MS = 90L;\n"
    "    private static final int EMPTY_FIELD_RETRY_ATTEMPTS = 4;\n"
)
if old not in undo:
    raise SystemExit("dev.14: retry constant marker not found")
undo = undo.replace(old, new, 1)

old = '''                session.restored = service != null
                        && restoreLastDeletion(service, origin);
                log("swipe-down restore attempted restored=" + session.restored);
'''
new = '''                session.restored = service != null
                        && restoreLastDeletion(service, origin);
                if (!session.restored && service != null) {
                    scheduleEmptyFieldRestoreRetry(service, origin);
                }
                log("swipe-down restore attempted restored=" + session.restored);
'''
if old not in undo:
    raise SystemExit("dev.14: swipe-down restore marker not found")
undo = undo.replace(old, new, 1)

marker = '''    private static void postTrailingBackspaceRepair(
            InputMethodService service, View backspaceView, DeletionRecord record) {
'''
addition = '''    private static void scheduleEmptyFieldRestoreRetry(
            InputMethodService service, View backspaceView) {
        if (service == null || backspaceView == null) {
            return;
        }
        DeletionRecord record = LAST_DELETION.get(service);
        if (record == null
                || !record.hasFullSnapshot
                || record.expectedAfterText == null
                || !record.expectedAfterText.isEmpty()) {
            return;
        }
        postEmptyFieldRestoreRetry(service, backspaceView, record, 1);
    }

    private static void postEmptyFieldRestoreRetry(
            InputMethodService service,
            View backspaceView,
            DeletionRecord record,
            int attempt) {
        backspaceView.postDelayed(() -> {
            DeletionRecord current = LAST_DELETION.get(service);
            if (current != record) {
                return;
            }
            boolean restored = restoreLastDeletion(service, backspaceView);
            log("empty-field undo retry attempt=" + attempt + " restored=" + restored);
            if (!restored
                    && attempt < EMPTY_FIELD_RETRY_ATTEMPTS
                    && LAST_DELETION.get(service) == record) {
                postEmptyFieldRestoreRetry(
                        service, backspaceView, record, attempt + 1);
            }
        }, EMPTY_FIELD_RETRY_DELAY_MS);
    }

'''
if marker not in undo:
    raise SystemExit("dev.14: repair method marker not found")
undo = undo.replace(marker, addition + marker, 1)

undo_path.write_text(undo, encoding="utf-8")

gradle = Path("gradle.properties")
text = gradle.read_text(encoding="utf-8")
lines = []
replaced = False
for line in text.splitlines():
    if line.strip().startswith("version ="):
        lines.append(f"version = {VERSION}")
        replaced = True
    else:
        lines.append(line)
if not replaced:
    raise SystemExit("dev.14: version marker not found")
gradle.write_text("\n".join(lines) + "\n", encoding="utf-8")

description = (
    f"### 🧪 {VERSION}\n\n"
    "* **修复文本末尾全文删除后无法撤销：** 当上滑删除使输入框变为空时，下滑若遇到 InputConnection 短暂为空或重建，会进行短时安全重试。\n"
    "* **不影响已修复场景：** 文本中间撤销仍立即恢复，尾随 Backspace 拦截和单字符自修复逻辑保持不变。\n"
    "* **安全限制保持：** 仅对完整快照且删除后预期为空的记录启用重试，并继续校验同一编辑器、30 秒撤销窗口及删除后的文本状态。\n"
)

bundle = {
    "created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S"),
    "description": description,
    "download_url": f"https://github.com/{REPO}/releases/download/{TAG}/patches-{VERSION}.mpp",
    "signature_download_url": "",
    "version": VERSION,
}
Path("patches-bundle.json").write_text(
    json.dumps(bundle, ensure_ascii=False, indent=4) + "\n", encoding="utf-8"
)
Path("release-notes-dev14.md").write_text(description + "\n", encoding="utf-8")
