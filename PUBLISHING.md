# Publishing this fork on GitHub

Target repository:

```text
DWcrea/Gboard-patches-Enhanced
```

GitHub repository description / 仓库描述：

```text
Enhanced Gboard patches with Backspace swipe gestures, numeric keypad support, Text Expansion, and Chinese input improvements. / 增强版 Gboard 补丁：支持删除键上滑、数字键盘适配、快捷文本与中文输入优化。
```

Visibility: **Public**

## Release channels / 发布通道

This repository uses two branches for Morphe remote-source updates:

- `main`: stable channel only.
- `dev`: prerelease channel only.

Morphe resolves the GitHub repository source to `patches-bundle.json` on `main`. When the user enables **Pre-release patches**, Morphe may also select a newer prerelease from the development channel.

Rules:

- Never point `main/patches-bundle.json` at a `-dev.N` release.
- Keep prerelease metadata on `dev`.
- Use `created_at` in `yyyy-MM-ddTHH:mm:ss` format, without a timezone suffix.
- Keep `download_url` exactly aligned with the GitHub Release asset name.
- Stable releases use `v<version>` tags and a normal GitHub Release.
- Development releases use versions containing `-dev.N` and are published as prereleases.

Current stable release:

```text
v3.11.0-enhanced.2
```

Current prerelease baseline before promotion:

```text
v3.11.0-enhanced.2-dev.18
```

## Publishing a stable release / 发布稳定版

1. Start from the latest verified development baseline.
2. Set `version` in `gradle.properties` to the stable version.
3. Update `patches-bundle.json` to the same version and stable GitHub Release asset URL.
4. Ensure diagnostics intended only for development are disabled by default.
5. Update `README.md`, `docs/`, and `CUSTOM_CHANGELOG.md`.
6. Merge/promote the verified tree to `main` while preserving the stable branch history.
7. Create and push tag `v<version>` from the stable commit, or run the Release workflow manually on that exact commit.
8. Verify the GitHub Release asset and Morphe source metadata.

## Publishing a prerelease / 发布预发布版

1. Work on `dev`.
2. Set `version` in `gradle.properties` to a version containing `-dev.N`.
3. Update `dev/patches-bundle.json` to the same version and prerelease Release asset URL.
4. Ensure `created_at` has no timezone suffix.
5. Push to `dev`; the remote-dev workflow builds, validates and publishes the prerelease bundle.
6. Test on-device before promoting any behavior to stable.

Do not copy prerelease metadata back to `main`. Stable Morphe users must continue receiving the latest stable bundle until a new stable release is published.

## Required GitHub secret

The release workflow requires a repository secret named:

```text
MORPHE_PACKAGES_TOKEN
```

It should contain a GitHub classic PAT with `read:packages` permission so the Morphe Gradle plugin can be resolved during GitHub Actions builds.

## Compatibility baseline

Stable `3.11.0-enhanced.2` is built for and verified against:

```text
Gboard 18.0.3.954559732-release-arm64-v8a
Morphe Gradle Patch Plugin 1.3.3
Gradle 9.6.1
Java 21
```

Keep the upstream `LICENSE`, `NOTICE`, historical attribution, and acknowledgement of `jasonwu1994/Gboard-patches` intact.
