# AntiSpeedrun Release Lifecycle & Publishing Guide

This document defines the versioning rules, release channels and publishing procedure for **AntiSpeedrun**.

---

## 1. Versioning Rules

Versions follow SemVer 2.0.0, applied in three phases. The phase decides the version
series and where a build is published.

* **Development** -- `v0.Y.Z`, with Y incremented for each finished feature group.
  `v0.1.0` is Epics 1-4,
  `v0.2.0` is Epic 6, `v0.3.0` is Epic 5 and `v0.4.0` is Epic 7. Config and commands may
  change between development builds. `config.yml` carries a `config-version` and is migrated
  on start (the old file is backed up first), so testers no longer regenerate it. The state
  files carry no format marker yet (#168 follow-up): keep a backup of `plugins/AntiSpeedrun/`
  and `playerdata` between builds. Increment Z for compatible bug fixes between
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
| Development | `v0.Y.Z`, any suffix | Pre-release, never Latest | Never |
| Alpha | `v1.0.0-alpha.N` (and later `vX.Y.Z-alpha.N`) | Pre-release | Modrinth `alpha`, Hangar `Alpha` |
| Beta | `v1.0.0-beta.N` (and later `vX.Y.Z-beta.N`) | Pre-release | Modrinth `beta`, Hangar `Beta` |
| Release candidate | `vX.Y.Z-rc.N` (X >= 1) | Pre-release | Never |
| Stable | `vX.Y.Z` (X >= 1) | Latest Release | Modrinth `release`, Hangar `Release` |

Development builds are never marked Latest and never go to a distributor. Distributor
publication starts at `v1.0.0-alpha.1`. A tag with major version `0` is refused for Modrinth
and Hangar whatever its suffix: the workflow skips both, and
`scripts/release-distributors.py` exits with an error if asked to publish one. Release
candidates are GitHub-only as well; neither distributor has a matching channel.

The release workflow derives the channel from the tag alone. Any tag with major version `0`
is a development build, with or without a suffix, so it is always a pre-release and never
Latest. `vX.Y.Z-rc.N` (X >= 1) is also accepted and published as a GitHub pre-release. Only
a suffix-free tag with X >= 1 becomes a full release. It is made Latest unless a higher
stable version is already published, so an older stable release never displaces a newer
one. Tags use
SemVer numbers without leading zeros; any other `v*` tag fails the workflow before it
builds anything. Publication to all three destinations is described in section 3.

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
git tag -a v1.0.0 -m "release: AntiSpeedrun v1.0.0"
git push origin v1.0.0
```

**Tag policy.** Release tags must be annotated (`git tag -a`). Signing is optional and
needs no key setup: the pipeline does not verify tag signatures. Its tag check is
source-commit verification only: the tag must point at the commit the workflow checked
out, and that commit must be on `main`. The existing GitHub pre-releases, including
v0.1.0, were published from unsigned annotated tags.

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
4. **Publish Release.** Runs in the protected `release` environment and starts only after
   the maintainer approves the run in the browser. It builds nothing. It publishes to
   GitHub, then, for a distributor tag, to Modrinth and Hangar. See Step 5.

The shipped JAR is not shaded: the plugin has no runtime dependencies to bundle, and the
build produces exactly one JAR.

A pull request that changes `release.yml` or a release script runs stages 1 to 3 as a
rehearsal against the placeholder tag `v0.0.0`. The publish job never starts on a pull
request.

### Step 4: Candidate identity and evidence

`manifest.json` records the schema, candidate identifier (`<run id>-<attempt>-<commit>`),
artifact name, repository, source commit SHA, tag, version, channel, pre-release and Latest
flags, run ID and attempt, the destinations, and the SHA-256 of every file. The
destinations are `github` (this repository, the tag) and, for a distributor tag only,
`modrinth` (version type, loaders, Minecraft versions) and `hangar` (channel, platform
versions, Folia support). Both listings declare exactly the servers the candidate boots on:
loaders, platforms and Minecraft versions are derived from `SERVER_LEGS` in
`scripts/release-candidate.py`, which a unit test keeps equal to the `Server Boot` matrix in
`ci.yml` and the `Candidate Server Boot` matrix in `release.yml`. Today that is Paper and
Folia on `1.21.4`, `1.21.11` and `26.2`; moving a pin moves the listings with it. The files
are:

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
no other job receives a publishing secret. GitHub publication uses the job's own
`GITHUB_TOKEN`. `MODRINTH_TOKEN` and `HANGAR_API_TOKEN` are secrets of the `release`
environment, so only the approved publish job can read them, and each is passed only to the
steps for its own destination. Approve the pending `release` deployment only after reading
the manifest and the distributor projects in the run summary. Automation never approves it.

The environment gate on its own fails open. If `release` does not exist, GitHub creates it
without protection when the job first names it. If it loses its required reviewer, the job
starts without waiting. The publish job's first step, **Require Maintainer Approval**,
therefore reads the run's approvals (`actions/runs/<id>/approvals`, the job's only use of
`actions: read`). It continues only if a person, not a bot or app, approved `release` for
this run, and it stops on any rejection. A tag pushed before the environment is set up fails
there without writing anything; set the environment up and re-run the failed job.

**Publication order.** After the approval check, the publish job:

1. Decides from the tag whether Modrinth and Hangar are destinations at all
   (`release-distributors.py plan`). For a development build or release candidate the
   remaining distributor steps are skipped.
2. For a distributor tag, **preflights Modrinth and Hangar** before anything public is
   written: the token and project are set, the project is public, the token may upload to
   it, the Hangar project carries the Supports Folia tag, and the version is either absent
   or already exactly the candidate. Any failure stops the run with all three destinations
   untouched.
3. Publishes to **GitHub** (below).
4. Publishes to **Modrinth**, then to **Hangar** (see *Distributor publication*).

**GitHub publication.** `scripts/release-github.py` runs in order:

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

**Distributor publication.** `scripts/release-distributors.py publish modrinth` and
`publish hangar` each run in order:

1. Refuse a tag that is not a distributor tag, then re-verify the candidate, the manifest,
   every digest and the evidence. The run's commit must equal the candidate's source commit,
   and the tag on `origin` must still point at it.
2. Look the version up with the token, so a version only project members can see still
   counts as present. On Modrinth the candidate JAR's SHA-512 is also looked up across all
   of Modrinth, which finds a version the project listing omits.
3. If it is **absent**, upload the candidate JAR as it is: the version number from the tag,
   the channel from the table in section 2, `release-notes.md` as the changelog, and the
   loaders, platforms and Minecraft versions from the manifest. Modrinth versions are
   named `AntiSpeedrun <version>` and are not featured.
4. If it is **present**, or after the upload, compare it with the candidate: version, name,
   channel, visibility, changelog, platforms, Minecraft versions, file name and the digest
   the registry reports. Then fetch the version signed out, download the file a consumer
   would get, and compare its SHA-256 with the manifest.

Both registries also refuse duplicates on their own: Modrinth rejects a file it already
hosts and Hangar rejects a second version with the same name. A lost upload response
therefore cannot produce a second copy; the next attempt finds the version and verifies it.

**Retrying.** Use **Re-run failed jobs** on the same run. It re-runs the publish job, which
needs approval again, against the candidate and evidence of the original attempt. The
scripts resume wherever the last attempt stopped. On GitHub that means creating the draft,
finishing uploads, publishing, or only re-verifying a complete release. On Modrinth and
Hangar a version already there is verified and never uploaded again, so a run in which
GitHub and Modrinth succeeded and Hangar failed uploads only to Hangar on the retry. Fix the
cause first: a missing secret, variable, channel or Folia tag, a project awaiting approval,
or a registry outage. Do not use **Re-run all
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
* **A Modrinth or Hangar version differs from the candidate**, or the candidate JAR is on
  Modrinth under another version. Compare it with the manifest in the run summary. If it
  is wrong, delete that version in the registry's web UI and re-run the failed job, which
  uploads the candidate. If the registry refuses the upload after a deletion, cut a new
  version instead.
* **Hangar holds the new version for review.** It then shows a visibility other than
  `public` and the job fails without changing anything. Re-run the failed job once the
  version is approved; it finds the version and verifies it.

### Maintainer setup

Configured once, in the repository settings in the browser:

* **Environments → `release`**, created before the first tag: required reviewer is the
  maintainer. Deployment branches and tags: **Selected**, with the tag rule `v*`. Without
  the reviewer, every publish run fails its approval check. GitHub publication needs no
  secret. Before the first distributor tag (`v1.0.0-alpha.1`), add two **environment
  secrets** to `release`, never as repository or organisation secrets:
  * `MODRINTH_TOKEN`: a Modrinth personal access token of a project member with upload
    permission, with the scopes `USER_READ`, `PROJECT_READ`, `VERSION_READ` and
    `VERSION_CREATE`.
  * `HANGAR_API_TOKEN`: a Hangar API key of a project member, with the `create_version`
    permission.
* **Variables → Repository variables**, so the approval summary can show them:
  * `MODRINTH_PROJECT`: the Modrinth project's slug or ID.
  * `HANGAR_PROJECT`: the Hangar project's slug.

  A distributor tag with any of the four missing fails in preflight, before anything is
  published. A development build needs none of them.
* **Modrinth project**: approved and public, since an unapproved project hides its
  versions from consumers. License, icon and description are set in its settings; versions
  carry only what the workflow sends.
* **Hangar project**: public, with **Supports Folia** enabled in its settings, and the
  channels `Alpha`, `Beta` and `Release` created. Each version is published for the Paper
  platform.
* **Rules → tag ruleset** (recommended): restrict creating, updating and deleting `v*` tags
  to the maintainer, so nothing else can start or move a release.

---

## 4. Distribution Platforms
* **GitHub Releases** -- Every release: development builds and alphas/betas as
  pre-releases, stable `vX.Y.Z` tags as full releases, through the pipeline in section 3.
* **Modrinth & Hangar** -- From `v1.0.0-alpha.1` onward, on the alpha, beta and release
  channels matching the table in section 2, through the same approved publish job as
  GitHub. Never a development build (`v0.Y.Z`, any suffix) or a release candidate.
