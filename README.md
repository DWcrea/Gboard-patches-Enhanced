<h1 align="center">Gboard Patches Enhanced</h1>

<p align="center">
  <strong>面向 Gboard 18.0.3 的增强型 Morphe 补丁集</strong><br>
  Enhanced Morphe patch set for Gboard 18.0.3
</p>

<p align="center">
  基于 <code>jasonwu1994/Gboard-patches</code> 二次开发，重点增强删除键手势、数字键盘、快捷文本与中文输入体验。
</p>

<p align="center">
  <a href="https://github.com/DWcrea/Gboard-patches-Enhanced/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/DWcrea/Gboard-patches-Enhanced?display_name=tag&label=Release&style=for-the-badge"></a>
  <a href="https://github.com/DWcrea/Gboard-patches-Enhanced/releases"><img alt="Total downloads" src="https://img.shields.io/github/downloads/DWcrea/Gboard-patches-Enhanced/total?label=Downloads&style=for-the-badge"></a>
  <a href="https://morphe.software/add-source?github=DWcrea/Gboard-patches-Enhanced"><img alt="Add to Morphe" src="https://img.shields.io/badge/Morphe-Add%20Source-00A8FF?style=for-the-badge"></a>
</p>

---

## 项目简介 / Overview

**Gboard Patches Enhanced** 是一个公开的 Gboard 补丁项目，基于 [jasonwu1994/Gboard-patches](https://github.com/jasonwu1994/Gboard-patches) 二次开发，并使用 **Morphe** 构建和应用补丁。

本项目不是重新实现 Gboard，而是在明确支持的目标版本上，通过 Patch 增强现有功能，同时保留上游项目的大部分补丁能力。

> **English:** This repository is a public derivative of the upstream Gboard Patches project. It keeps the upstream patch collection and adds verified improvements for Backspace gestures, numeric keypad behavior, Text Expansion and Chinese input workflows.

## 当前稳定版 / Stable Release

```text
Gboard Patches Enhanced: 3.11.0-enhanced.2
Target Gboard: 18.0.3.954559732-release-arm64-v8a
ABI: arm64-v8a
Official package: com.google.android.inputmethod.latin
Default renamed package: dev.jason.com.google.android.inputmethod.latin
Morphe Gradle Patch Plugin: 1.3.3
Gradle: 9.6.1
Java / JDK: 21
License: GPLv3
```

> [!IMPORTANT]
> 当前补丁针对 **Gboard 18.0.3.954559732-release-arm64-v8a** 进行绑定、测试和实机验证。Gboard 更新后，混淆类名、方法签名、候选链和输入事件路径都可能变化，不应默认跨版本兼容。

---

## 主要增强功能 / Enhanced Features

### 1. 删除键上滑：删除光标前全部内容

普通键盘中，从 Backspace 向上滑动，可一次删除光标前的全部内容，并保留光标后的文本。

```text
abc|DEF  →  上滑删除键  →  |DEF
```

同时提供撤销/恢复相关兼容处理，避免一次误触造成不可逆的大段删除。

### 2. 数字键盘 / 电话键盘删除手势

部分数字与电话布局不走普通字母键盘的 MotionEvent 路径，因此本 Fork 增加了额外 fallback，使删除键上滑在这些布局中也能工作。

### 3. 中文下滑数字后的半角标点

中文输入状态下，通过下滑输入数字后，如果紧接着输入全角标点，会自动规范为半角形式：

```text
1： → 1:
3。 → 3.
3． → 3.
```

### 4. 快捷文本 / Text Expansion

稳定版支持自定义：

```text
快捷码 → 展开文本
```

适合手机号、邮箱、地址、网址、常用句子等内容。

示例：

```text
sjh=13800138000
yx=name@example.com
dz=辽宁省大连市……
```

核心行为：

- 中文输入时记录真实 raw token，不依赖当前第一中文候选。
- 输入完整快捷码后，展开文本显示在候选栏第一位置。
- 可直接点击第一候选上屏。
- 也可继续按空格、回车或常用标点展开。
- 支持“保留触发符”开关。
- 支持 JSON 批量导入 / 导出。
- 最多 200 条规则。
- 快捷码大小写不敏感。
- 密码输入框自动禁用。
- 诊断工具保留，但稳定版默认关闭。

例如：

```text
sjh → 第一候选：13800138000
```

或者：

```text
sjh + 空格 → 13800138000 + 空格
```

开发阶段曾出现“有时能展开、有时不能”的问题。最终通过设备诊断确认，根因是 Gboard 内部 `selectedCode` 在部分路径中是 Android `KeyEvent` keyCode，而不是 Unicode 字符码。稳定版已修复 Backspace、字母键、Space、Enter 等事件对 raw token 的污染。

详细说明见：[docs/TEXT_EXPANSION.md](docs/TEXT_EXPANSION.md)

### 5. 全局简体中文界面

Gboard Patches 设置页的中文资源统一为简体中文，并将部分台湾地区常用 UI 术语归一化为大陆简体用语，例如：

```text
汇入 / 汇出 → 导入 / 导出
设定 → 设置
辨识 → 识别
透过 → 通过
工具列 → 工具栏
剪贴簿 → 剪贴板
```

---

## 快捷文本批量导入 / Bulk Text Expansion Import

快捷文本使用可移植 JSON 格式 `gboard-text-expansion.v1`：

```json
{
  "format": "gboard-text-expansion.v1",
  "enabled": true,
  "keepTrigger": true,
  "entries": [
    {
      "shortcut": "sjh",
      "text": "13800138000"
    },
    {
      "shortcut": "yx",
      "text": "name@example.com"
    }
  ]
}
```

设置位置：

```text
Gboard Patches
→ 键盘工具与快捷操作
→ 快捷文本
→ 导入 JSON
```

如果已有 Gboard 个人字典、CSV、TXT、JSON 或其他快捷短语列表，可以转换成上述格式后一次性导入。

---

## Included Patches / 上游与项目补丁

本 Fork 保留上游大量 Patch，并在此基础上追加增强。包括但不限于：

### Project-built / Runtime features

- Clipboard Enhancements
- Web Clipboard
- Floating Web Search
- Custom Theme
- FTP Server
- Long-Press Editing Shortcuts
- Toolbar Editing Buttons
- Swipeable Custom Top Row
- Incognito Mode Toggle
- Custom Symbols
- Simple Calculator
- G Logo on Spacebar
- Rounded Keyboard Panel
- Latin Globe Key Ignore Interval
- Emojis, stickers & GIFs Tab Order
- Backup & Restore
- Text Expansion / 快捷文本

### Gboard feature unlocks

- AI Writing Tools
- Advanced Voice Typing
- OCR / Scan Text
- English QWERTY Up-Flick Uppercase
- Inline Autofill Suggestions
- Grammar Checker
- Smart Compose / Inline Suggestions
- Key Shape Selection
- Bluetooth Microphone
- Emoji Size
- Cursor Trackpad Mode
- Split Keyboard
- Accessibility Layout
- Quick Insert
- Hyperspeed Typing Animation
- Clipboard Custom Character Limit
- Access Points menu style
- Top Toolbar Item Count
- Settings Homepage Override
- Developer Options / Flag Editor
- Package Rename

### Taiwan / Zhuyin-focused features

- Zhuyin Slide Input
- Zhuyin Quick Traditional/Simplified Toggle
- Zhuyin Bottom Row Key Sizes

具体功能会随上游版本与目标 Gboard 绑定变化，请以当前 `patches-list.json` 与 Morphe 中显示的 Patch 列表为准。

---

## 安装 / Install

### Morphe Source

推荐直接添加本仓库：

- [一键添加到 Morphe](https://morphe.software/add-source?github=DWcrea/Gboard-patches-Enhanced)
- 手动 Source：`https://github.com/DWcrea/Gboard-patches-Enhanced`

当前稳定版：

```text
v3.11.0-enhanced.2
patches-3.11.0-enhanced.2.mpp
```

### 推荐流程

1. 准备干净的目标 Gboard：`18.0.3.954559732-release-arm64-v8a`。
2. 在 Morphe 中添加本仓库 Source，或使用 Release 中的 `.mpp`。
3. 选择需要的 Patch。
4. 建议启用 **Package Rename**，便于与官方 Gboard 并存。
5. 从原版 APK 重新生成 patched APK。
6. 安装后进入 Gboard Patches 设置页确认功能。
7. 先测试基本输入、删除、候选、剪贴板和语言切换，再作为日常输入法使用。

> [!NOTE]
> 如果卸载补丁版 Gboard 后重新安装，Gboard 自己下载的语言模型或应用私有数据可能需要重新初始化，因此测试时建议等待语言包和候选模型准备完成后再比较行为。

---

## Stable / Dev 发布通道

仓库使用两个发布通道：

```text
main → stable
 dev  → prerelease
```

稳定用户应使用 `main` 对应的 Source 元数据；开发测试使用 `dev` / prerelease。

发布规则见：[PUBLISHING.md](PUBLISHING.md)

---

## Build / 本地构建

Morphe 构建插件通过 GitHub Packages 分发。本地构建前需要 GitHub Token 的 `read:packages` 权限。

Gradle 用户配置：

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PAT_WITH_PACKAGE_READ_ACCESS
```

或使用环境变量：

```text
GITHUB_ACTOR
GITHUB_TOKEN
```

Windows PowerShell：

```powershell
$env:GITHUB_ACTOR = "YOUR_GITHUB_USERNAME"
$env:GITHUB_TOKEN = "YOUR_GITHUB_PAT"
```

构建：

```powershell
.\gradlew.bat :patches:buildAndroid
```

完整验证：

```powershell
.\gradlew.bat :patches:test :patches:buildAndroid generatePatchesList
```

生成内容：

```text
patches/build/libs/*.mpp
patches-list.json
patches-bundle.json
```

GitHub Actions 发布需要 Repository Secret：

```text
MORPHE_PACKAGES_TOKEN
```

---

## 项目结构 / Repository Structure

```text
Gboard-patches-Enhanced/
├─ patches/                 # Morphe Patch 主体与注册
├─ extensions/              # 注入 Gboard 的运行时逻辑
├─ docs/                    # 功能说明与调试文档
├─ gradle/                  # Gradle Wrapper
├─ patches-bundle.json      # Morphe Source / Release 元数据
├─ patches-list.json        # Patch 清单
├─ CUSTOM_CHANGELOG.md      # 本 Fork 更新记录
├─ PUBLISHING.md            # 发布流程
└─ README.md
```

核心增强代码主要位于：

```text
extensions/extension/.../longpressquickactions/
extensions/extension/.../textexpansion/
patches/.../features/longpressquickactions/
```

---

## 开发说明 / Development Notes

删除键上滑和快捷文本都涉及 Gboard 18.0.3 的混淆输入链。开发过程中已经确认：

- pointer owner 可能在滑动时 retarget 到相邻按键。
- MotionEvent 与 SoftKeyView 存在坐标空间差异。
- 中文 composing 状态不能只依赖可见候选词。
- `selectedCode` 在部分路径中是 Android keyCode，不应直接转换为 Unicode 字符。
- 迁移到新 Gboard 版本时，应重新确认 pointer owner、MotionEvent dispatch、SoftKeyView binding、input-event dispatcher、candidate layout 与 InputConnection 行为。

相关文档：

- [删除键上滑说明](docs/BACKSPACE_SWIPE_UP_CLEAR.md)
- [快捷文本说明](docs/TEXT_EXPANSION.md)
- [自定义更新记录](CUSTOM_CHANGELOG.md)

---

## 鸣谢 / Acknowledgements

感谢以下项目、作者与工具：

- **[jasonwu1994/Gboard-patches](https://github.com/jasonwu1994/Gboard-patches)**  
  本项目的上游来源。绝大多数基础 Patch、Morphe 项目结构、Gboard 版本绑定、运行时扩展和已有功能均来自该项目及其作者 **Jason Wu**。

- **[Morphe](https://morphe.software/)**  
  提供 Android Patch 框架、补丁格式和 Source 工作流。

- **Google Gboard**  
  本项目的目标应用。Gboard、Google 及相关商标归其权利人所有。

- **开源社区与实机测试反馈**  
  感谢所有提供源码、文档、Issue、逆向思路、实机测试和问题反馈的人。本 Fork 的删除键手势与快捷文本稳定性均经过多轮设备测试和诊断修复。

> 本 Fork 保留上游 LICENSE、NOTICE 和作者归属。新增修改继续以 GPLv3 条款公开。

---

## English Summary

**Gboard Patches Enhanced** is a public derivative of `jasonwu1994/Gboard-patches`, targeting **Gboard 18.0.3.954559732-release-arm64-v8a**.

Main additions in stable `3.11.0-enhanced.2`:

- Swipe up on Backspace to delete all text before the cursor while preserving the suffix.
- Numeric / phone keypad fallback for the same Backspace gesture.
- Chinese slide-down digit punctuation normalization.
- Stable Text Expansion with raw-key matching, first-candidate preview, tap-to-expand, Space / Enter / punctuation triggers, JSON import/export and password-field protection.
- Simplified Chinese UI normalization for the patch settings.

Build stack:

```text
Morphe Gradle Patch Plugin 1.3.3
Gradle 9.6.1
Java 21
GPLv3
```

---

## License

本项目基于 **GNU General Public License v3.0 (GPLv3)** 发布，并继续遵循上游项目许可证要求。

详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

### 免责声明 / Disclaimer

本项目为非官方第三方修改项目，仅用于学习、研究与个人定制。  
本项目与 Google、Gboard、Morphe 及其维护者不存在官方隶属、合作或背书关系。

Google、Gboard、Morphe 及其他名称、商标与产品标识归各自权利人所有。
