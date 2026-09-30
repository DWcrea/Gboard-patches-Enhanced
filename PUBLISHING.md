# Publishing this fork on GitHub

Target repository:

```text
DWcrea/Gboard-patches-Enhanced
```

GitHub repository description / 仓库描述：

```text
Enhanced Gboard patches with Backspace swipe gestures, numeric keypad support, and Chinese input improvements. / 增强版 Gboard 补丁：支持删除键上滑手势、数字键盘适配和中文输入优化。
```

Visibility: **Public**

First release tag:

```text
v3.11.0-enhanced.1
```

The runtime/release URLs in this working tree have been updated for the repository above. Keep the upstream `LICENSE`, `NOTICE`, historical `CHANGELOG.md` links, and README attribution intact.

The release workflow requires a repository secret named:

```text
MORPHE_PACKAGES_TOKEN
```

It should contain a GitHub classic PAT with `read:packages` permission so the Morphe Gradle plugin can be resolved during GitHub Actions builds.
