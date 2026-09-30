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

## Release channels / 发布通道

This repository uses two branches for Morphe remote-source updates:

- `main`: stable channel only.
- `dev`: prerelease channel only.

Morphe resolves a GitHub repository source to `patches-bundle.json` on `main`. When the user enables **Pre-release patches**, Morphe automatically checks the same file on the `dev` branch as well and selects the newer version.

Therefore:

- Never point `main/patches-bundle.json` at a `-dev.N` release.
- Keep prerelease metadata in `dev/patches-bundle.json`.
- Use `created_at` in `yyyy-MM-ddTHH:mm:ss` format, without a timezone suffix.
- Keep `download_url` exactly aligned with the GitHub Release asset name.

Current stable release:

```text
v3.11.0-enhanced.1
```

Current prerelease line:

```text
v3.11.0-enhanced.2-dev.N
```

## Publishing a stable release / 发布稳定版

1. Work on `main`.
2. Set `version` in `gradle.properties` to the stable version.
3. Update `main/patches-bundle.json` to the same version and stable Release asset URL.
4. Ensure `created_at` has no timezone suffix.
5. Create and push tag `v<version>`.
6. GitHub Actions builds the `.mpp` and publishes a normal GitHub Release.

## Publishing a prerelease / 发布预发布版

1. Work on `dev`.
2. Set `version` in `gradle.properties` to a version containing `-dev.N`.
3. Update `dev/patches-bundle.json` to the same version and prerelease Release asset URL.
4. Ensure `created_at` has no timezone suffix.
5. Create and push tag `v<version>` from the `dev` commit.
6. GitHub Actions publishes the release with `prerelease: true`.

Do not copy prerelease metadata back to `main`. Stable Morphe users must continue receiving the latest stable bundle until a new stable release is published.

The runtime/release URLs in this working tree have been updated for the repository above. Keep the upstream `LICENSE`, `NOTICE`, historical `CHANGELOG.md` links, and README attribution intact.

## Required GitHub secret

The release workflow requires a repository secret named:

```text
MORPHE_PACKAGES_TOKEN
```

It should contain a GitHub classic PAT with `read:packages` permission so the Morphe Gradle plugin can be resolved during GitHub Actions builds.
