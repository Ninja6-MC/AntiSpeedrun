# AntiSpeedrun Release Lifecycle & Publishing Guide

This document defines the versioning rules, release channels and publishing procedure for **AntiSpeedrun**.

---

## 1. Versioning Rules

Versions follow SemVer 2.0.0, applied in three phases. The phase decides the version
series and where a build is published.

* **Development** -- `v0.Y.Z`, one build per finished feature group. `v0.1.0` is Epics 1-4,
  `v0.2.0` is Epic 6, `v0.3.0` is Epic 5 and `v0.4.0` is Epic 7. Config and commands may
  change between development builds, so testers regenerate `config.yml` between builds
  until config versioning (#168) lands.
* **Feature-complete** -- `v1.0.0-alpha.1` once every feature group has shipped. A new
  alpha (`alpha.2`, ...) is cut whenever the fixes since the last one include a major
  change; minor fixes wait for the next alpha.
* **Alpha settled** -- `v1.0.0-beta.N`, incremented the same way as the alphas.
* **Stable** -- `v1.0.0`.

After `v1.0.0`, MAJOR is a breaking configuration schema, incompatible command or
permission change, or architecture rewrite; MINOR is a new gameplay feature or gate; PATCH
is a bug fix, optimization or small documentation correction.

---

## 2. Release Channels

| Phase | Tag Pattern | GitHub Release Type | Modrinth / Hangar |
| :--- | :--- | :--- | :--- |
| Development | `v0.Y.Z` | Pre-release, never Latest | None |
| Alpha | `v1.0.0-alpha.N` | Pre-release | Alpha channel |
| Beta | `v1.0.0-beta.N` | Pre-release | Beta channel |
| Stable | `v1.0.0` | Latest Release | Release channel |

Development builds are never marked Latest and never go to a distributor. Distributor
publication starts at `v1.0.0-alpha.1`. GitHub publication is implemented by #140 and
distributor publication by #167; this document does not describe their mechanics.

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
* **GitHub Releases** -- Every release: development builds and alphas/betas as
  pre-releases, `v1.0.0` as the full release. Implemented by #140.
* **Modrinth & Hangar** -- From `v1.0.0-alpha.1` onward, on the alpha, beta and release
  channels matching the table in section 2. Implemented by #167.
