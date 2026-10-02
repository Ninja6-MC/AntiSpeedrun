# AntiSpeedrun Release Lifecycle & Publishing Guide

This document defines the versioning rules, release channels and publishing procedure for **AntiSpeedrun**.

---

## 1. Versioning Rules

Versions follow SemVer 2.0.0, applied in three phases. The phase decides the version
series and where a build is published.

* **Development** -- `v0.Y.Z`, with Y incremented for each finished feature group.
  `v0.1.0` is Epics 1-4,
  `v0.2.0` is Epic 6, `v0.3.0` is Epic 5 and `v0.4.0` is Epic 7. Config and commands may
  change between development builds, so testers regenerate `config.yml` between builds
  until config versioning (#168) lands. Increment Z for compatible bug fixes between
  feature-group releases, such as `v0.1.1`; `v0.2.0` remains Epic 6.
* **Feature-complete** -- `v1.0.0-alpha.1` once every feature group has shipped. A new
  alpha (`alpha.2`, ...) is cut whenever the fixes since the last one include a major
  change; minor fixes wait for the next alpha.
* **Alpha settled** -- `v1.0.0-beta.N`, incremented the same way as the alphas.
* **Stable** -- `v1.0.0`, and every later `vX.Y.Z` (X >= 1) stable tag.

After `v1.0.0`, MAJOR is a breaking configuration schema, incompatible command or
permission change, or architecture rewrite; MINOR is a new gameplay feature or gate; PATCH
is a bug fix, optimization or small documentation correction.

---

## 2. Release Channels

| Phase | Tag Pattern | GitHub Release Type | Modrinth / Hangar |
| :--- | :--- | :--- | :--- |
| Development | `v0.Y.Z` | Pre-release, never Latest | None |
| Alpha | `v1.0.0-alpha.N` (and later `vX.Y.Z-alpha.N`) | Pre-release | Alpha channel |
| Beta | `v1.0.0-beta.N` (and later `vX.Y.Z-beta.N`) | Pre-release | Beta channel |
| Stable | `vX.Y.Z` (X >= 1) | Latest Release | Release channel |

Development builds are never marked Latest and never go to a distributor. Distributor
publication starts at `v1.0.0-alpha.1`.

The release workflow derives the channel from the tag alone. Any tag with major version `0`
is a development build, with or without a suffix, so it is always a pre-release and never
Latest. `vX.Y.Z-rc.N` (X >= 1) is also accepted and published as a GitHub pre-release. Only
a suffix-free tag with X >= 1 becomes a full release. It is made Latest unless a higher
stable version is already published, so an older stable release never displaces a newer
one. Tags use
SemVer numbers without leading zeros; any other `v*` tag fails the workflow before it
builds anything. GitHub publication is described in section 3; distributor publication is
#167.

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

Releases follow `N6-REL-03`: `.github/workflows/release.yml` builds the candidate once,
boots those exact bytes, waits for maintainer approval, and publishes the same bytes
unchanged.

### Step 1: Pre-release checklist
* `main` CI is green (`Build and Test`, the `Server Boot` legs, `DCO`, `Standards`) on the
  commit to be tagged.
* For a **stable** release, `CHANGELOG.md` has a `## [X.Y.Z]` section. The workflow fails a
  stable tag without one. A pre-release uses its own section if there is one, then the
  `X.Y.Z` section, then `## [Unreleased]`.
* The version comes from the tag. The workflow passes it to Gradle as `-PpluginVersion`, so
  the fallback version in `build.gradle.kts` does not need to change.

### Step 2: Cut the tag
Tag a commit that is already on `main`. The workflow refuses any other commit.

```bash
git checkout main
git pull
git tag -s v1.0.0 -m "release: AntiSpeedrun v1.0.0"
git push origin v1.0.0
```

### Step 3: Automated stages

The tag push starts **Release** (`release.yml`) at the tagged commit.

1. **Build Release Candidate.** Validates the tag, checks that it names the checked-out
   commit and that the commit is on `main`, runs the script tests, then runs
   `./gradlew test build -PpluginVersion=<version>` once, without the Gradle build cache.
   `scripts/release-candidate.py create` requires `build/libs` to hold only
   `AntiSpeedrun-<version>.jar`. It copies that JAR, writes its `.sha256` file and the
   release notes, checks the JAR (see Step 4), and seals `manifest.json`. The candidate
   is retained as the workflow artifact `release-candidate-<run id>-<attempt>`.
2. **Candidate Server Boot.** Six jobs download the retained candidate and verify it before
   using it. Each boots it on one of the pinned Paper and Folia servers of the `Server Boot`
   matrix in `ci.yml` (`scripts/boot-smoke.py`). A passing leg writes a receipt bound to
   the candidate identifier, the manifest digest and every file digest.
3. **Bind Candidate Evidence.** Verifies the candidate again and accepts only one passing
   receipt per leg, all for this candidate. It writes `evidence.json` and shows the manifest
   in the run summary for review.
4. **Publish GitHub Release.** Runs in the protected `release` environment and starts only
   after the maintainer approves the run in the browser. It builds nothing. See Step 5.

The shipped JAR is not shaded: the plugin has no runtime dependencies to bundle, and the
build produces exactly one JAR.

A pull request that changes `release.yml` or either release script runs stages 1 to 3 as a
rehearsal against the placeholder tag `v0.0.0`. The publish job never starts on a pull
request.

### Step 4: Candidate identity and evidence

`manifest.json` records the schema, candidate identifier (`<run id>-<attempt>-<commit>`),
artifact name, repository, source commit SHA, tag, version, channel, pre-release and Latest
flags, run ID and attempt, the destination (`github`, this repository, the tag), and the
SHA-256 of every file:

* `AntiSpeedrun-<version>.jar`, the plugin;
* `AntiSpeedrun-<version>.jar.sha256`, its checksum line;
* `release-notes.md`, the changelog section used as the release body.

Every stage after the build calls `release-candidate.py verify` on its download. It
recomputes version and channel from the tag rather than trusting the manifest, and checks
every manifest field against this run. The file list must match exactly, with no extra
file, and every digest must match. The JAR must contain exactly one `plugin.yml` and one
`config.yml`, plus the main class. Its packaged `plugin.yml` must declare `name:
AntiSpeedrun`, `version:` equal to the tag without `v`, the expected `main:` and
`folia-supported: true`. The boot test then runs those same downloaded bytes.

The candidate, receipts and evidence are kept for **30 days**, the artifact retention set
in the workflow. Approve and finish the release within that time. After it, the artifacts
are gone and the publish job fails; push a new tag rather than rebuild the old one.

### Step 5: Approval, publication and recovery

**Approval.** Only the publish job holds `contents: write`. Every other job reads only, and
no job receives a publishing secret: GitHub publication uses the job's own `GITHUB_TOKEN`.
Approve the pending `release` deployment only after reading the manifest in the run
summary. Automation never approves it.

The environment gate on its own fails open. If `release` does not exist, GitHub creates it
without protection when the job first names it. If it loses its required reviewer, the job
starts without waiting. The publish job's first step, **Require Maintainer Approval**,
therefore reads the run's approvals (`actions/runs/<id>/approvals`, the job's only use of
`actions: read`). It continues only if a person, not a bot or app, approved `release` for
this run, and it stops on any rejection. A tag pushed before the environment is set up fails
there without writing anything; set the environment up and re-run the failed job.

**Publication.** `scripts/release-github.py` runs in order:

1. Re-verify the candidate, the manifest, every digest and the evidence. The run's commit
   must equal the candidate's source commit, and the tag on GitHub must still point at it.
   A tag moved after the candidate was built fails the run.
2. Look up every release carrying the tag, drafts included. More than one stops the run.
3. With none, create a **draft** titled `AntiSpeedrun <tag>` with `release-notes.md` as its
   body and the candidate's pre-release flag. Its `target_commitish` is the candidate's
   source commit, so if the tag disappeared before publishing, GitHub would recreate it
   there and not at the head of `main`.
4. Complete the draft. An asset GitHub never finished receiving is deleted; drafts are not
   public. An uploaded asset must match its manifest digest byte for byte. Missing assets
   are uploaded from the candidate.
5. Re-read the draft, check every asset again, re-check the tag, then publish. A
   pre-release is published with `make_latest=false`. A stable release is published with
   `make_latest=true` only when no published stable release has a higher SemVer version.
   Otherwise it gets `make_latest=false`. This covers re-running an older release's failed
   job, or approving two stable tags out of order. It does not cover two stable publish
   jobs **running at the same time**. The concurrency group is per tag, so both can decide
   before either publishes, and an older release published last can take Latest. Step 6
   catches this and fails the older job, but does not prevent it. A shared concurrency
   group would prevent it, but would also cancel a tag run waiting for approval. Approve
   one stable release at a time.
6. Download each public asset signed out, from the address a user would use, and compare
   it with the manifest. Then check Latest: a stable release with no higher stable release
   published must now be GitHub's Latest, and any other release must not be.

**Retrying.** Use **Re-run failed jobs** on the same run. It re-runs the publish job, which
needs approval again, against the candidate and evidence of the original attempt. The
script resumes wherever the last attempt stopped: it creates the draft, finishes uploads,
publishes, or only re-verifies a release that is already complete. Do not use **Re-run all
jobs** after anything was published: that builds a new candidate, and its bytes are checked
against the published release and rejected on any difference.

**Reconciling.** The run stops without writing anything public when a published release
differs from the candidate: different bytes, assets, title, notes or pre-release flag, or
a Latest flag other than the one step 6 expects. It also stops for a draft carrying a foreign or
mismatched asset, or several releases for one tag. Nothing public is overwritten or
deleted. Inspect the release in the browser:

* **An older stable release took Latest** (two stable publish jobs ran at once). The release
  itself is correct, so do not delete it. Edit the newer stable release in the browser and
  mark it Latest, then re-run the older release's failed job; it now finds everything complete.
* **Anything else is wrong with the release:** delete the release, never the tag, and
  re-run the failed job. If the tag must change, it names a new version.

### Maintainer setup

Configured once, in the repository settings in the browser:

* **Environments → `release`**, created before the first tag: required reviewer is the
  maintainer. Deployment branches and tags: **Selected**, with the tag rule `v*`. No
  environment secrets are needed for GitHub publication. Without the reviewer, every
  publish run fails its approval check.
* **Rules → tag ruleset** (recommended): restrict creating, updating and deleting `v*` tags
  to the maintainer, so nothing else can start or move a release.

---

## 4. Distribution Platforms
* **GitHub Releases** -- Every release: development builds and alphas/betas as
  pre-releases, stable `vX.Y.Z` tags as full releases, through the pipeline in section 3.
* **Modrinth & Hangar** -- From `v1.0.0-alpha.1` onward, on the alpha, beta and release
  channels matching the table in section 2. Implemented by #167.
