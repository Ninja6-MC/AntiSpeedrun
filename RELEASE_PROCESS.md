# AntiSpeedrun Release Lifecycle & Publishing Guide

This document defines the versioning rules, release channels and publishing procedure for **AntiSpeedrun**.

---

## 1. Versioning Rules (SemVer 2.0.0)

* **MAJOR** — Breaking configuration schema shifts, incompatible command/permission changes, or fundamental architecture rewrites.
* **MINOR** — New gameplay features, newly supported advancements or gates, new progression mechanics, or boss scaling additions.
* **PATCH** — Bug fixes, performance optimizations, or small documentation corrections.
* **Pre-releases** — `-alpha.N`, `-beta.N`, `-rc.N`.

---

## 2. Release Channels

| Channel | Tag Pattern | GitHub Release Type |
| :--- | :--- | :--- |
| Alpha | `vX.Y.Z-alpha.N` | Pre-release |
| Beta | `vX.Y.Z-beta.N` | Pre-release |
| Stable | `vX.Y.Z` | Latest Release |

### CI snapshots

CI builds are not releases. Every CI run names its JAR, and the `version:` in its
`plugin.yml`, after the commit it built: the nearest reachable release tag (`vX.Y.Z`,
optionally with `-alpha.N`, `-beta.N` or `-rc.N`, matched in full) without its leading `v`,
then the distance and commit, for example `1.0.0-beta.1-4-g1a2b3c4`
(`1.0.0-beta.1-0-g1a2b3c4` on the tagged commit itself). With no such tag reachable it is
`0.0.0-SNAPSHOT-g1a2b3c4`. `scripts/snapshot-version.py` derives it and
`scripts/verify-snapshot-artifact.py` checks the packaged JAR before upload (`N6-CI-09`). A
build without `-PpluginVersion` keeps the version in `build.gradle.kts`.

---

## 3. How to Execute a Release

### Step 1: Pre-release checklist
* `main` branch CI is green (`Build and Test`, `DCO`, `Standards`).
* `build.gradle.kts` version matches the target tag without the leading `v`.
* For a **stable** release, `CHANGELOG.md` contains a dedicated `## [X.Y.Z]` release section.

### Step 2: Cut the tag
```bash
git checkout main
git pull
git tag -s v1.0.0 -m "release: AntiSpeedrun v1.0.0"
git push origin v1.0.0
```

---

## 4. Distribution Platforms
* **GitHub Releases** — Primary release channel with compiled Shadow JAR and source assets.
* **Modrinth & Hangar** — Official public plugin listings.
