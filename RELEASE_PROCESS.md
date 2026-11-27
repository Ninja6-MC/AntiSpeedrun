# AntiSpeedrun Release Lifecycle & Publishing Guide

This document defines the versioning rules, release channels and publishing procedure for **AntiSpeedrun**.

---

## 1. Versioning Rules

Versions follow SemVer 2.0.0, applied in three phases. The phase decides the version
series and where a build is published.

* **Development** -- `v0.Y.Z`, with Y incremented for each finished feature group.
  `v0.1.0` is Epics 1-5, `v0.2.0` is Epics 6 and 7, `v0.3.0` is Epic 9 (first published
  as `v0.3.1` because `v0.3.0` was tagged but never published) and `v0.4.0` is Epic 10
  (first published as `v0.4.1`, for the same reason). `v0.5.0` is Epic 11
  (personal collection and brewing progression); its publication is still pending.
  Version numbers follow published builds, not planned feature groups. Config and commands may
  change between development builds. `config.yml` carries a `config-version` and is migrated
  on start (the old file is backed up first), so testers no longer regenerate it. The state
  files carry a `state-version` since 0.3.0 and player persistent data is not versioned: keep
  a backup of `plugins/AntiSpeedrun/` and `playerdata` between builds. Increment Z for compatible bug fixes between
  feature-group releases, such as `v0.1.1`; `v0.2.0` remains Epics 6 and 7.
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
and Hangar whatever its suffix: the publisher refuses to select either for it, and
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

Releases follow `N6-REL-03` and `N6-REL-05`. Two workflows share the work:

* **Release Candidate** (`.github/workflows/release-candidate.yml`) runs when a tag is
  pushed. It builds the candidate once, boots those exact bytes and binds the passing
  evidence to them. It publishes nothing and holds no publishing credential.
* **Publish Release** (`.github/workflows/release.yml`) is started by hand on the same tag,
  with the candidate run's ID. It checks everything it can without credentials, waits for
  maintainer approval, and then publishes the same bytes unchanged, one job per
  destination.

### Step 1: Pre-release checklist
* `main` CI is green (`Build and Test`, the `Server Boot` legs, `DCO`, `Standards`) on the
  commit to be tagged.
* For a **stable** release, `CHANGELOG.md` has a `## [X.Y.Z]` section. The candidate
  workflow fails a stable tag without one. A pre-release uses its own section if there is
  one, then the `X.Y.Z` section, then `## [Unreleased]`.
* The version comes from the tag. The workflow passes it to Gradle as `-PpluginVersion`, so
  the fallback version in `build.gradle.kts` does not need to change.

### Step 2: Cut the tag
Tag a commit that is already on `main`. The candidate workflow refuses any other commit.

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

**The workflows run as they are at the tag.** A dispatch on a tag runs the publisher
file of the tagged commit, so only a tag cut after the publisher was added (v0.5.0 and
earlier predate it) can be published by it.

**Tag the tip of `main`, and hold workflow changes until approval.** Until a release made
since the fix for #238 has published with workflow changes on `main` that its tag lacks, tag
the tip of `main` and merge nothing that touches `.github/workflows/` between tagging and
approving the publisher run. v0.3.0 and v0.4.0 failed to publish with HTTP 403 when such a
change merged first.

### Step 3: The candidate workflow

The tag push starts **Release Candidate** at the tagged commit.

1. **Build Release Candidate.** Validates the tag, checks that it names the checked-out
   commit and that the commit is on `main`, runs the script tests, then runs
   `./gradlew test build -PpluginVersion=<version>` once, without the Gradle build cache.
   `scripts/release-candidate.py create` requires `build/libs` to hold only
   `AntiSpeedrun-<version>.jar`. It copies that JAR, writes its `.sha256` file and the
   release notes, checks the JAR (see Step 4), and seals `manifest.json`. The candidate
   is retained as the workflow artifact `release-candidate-<run id>-<attempt>`.
2. **Candidate Server Boot.** Seven jobs download the retained candidate and verify it before
   using it. Each boots it on one of the pinned Paper and Folia servers of the `Server Boot`
   matrix in `ci.yml` (`scripts/boot-smoke.py`). A passing leg writes a receipt bound to
   the candidate identifier, the manifest digest and every file digest.
3. **Bind Candidate Evidence.** Verifies the candidate again and accepts only one passing
   receipt per leg, all for this candidate. It writes `evidence.json`, retained as
   `release-evidence-<run id>-<attempt>`, and shows the manifest in the run summary with the
   command that starts the publisher for this run.

The shipped JAR is not shaded: the plugin has no runtime dependencies to bundle, and the
build produces exactly one JAR.

A pull request that changes either release workflow or a release script runs the candidate
workflow as a rehearsal against the placeholder tag `v0.0.0`. The publisher cannot run on a
pull request; its scripts are covered by the unit tests that the rehearsal and CI run.

### Step 4: Candidate identity and evidence

`manifest.json` records the schema, candidate identifier (`<run id>-<attempt>-<commit>`),
artifact name, repository, source commit SHA, tag, version, channel, pre-release and Latest
flags, run ID and attempt, the destinations, and the SHA-256 of every file. The
destinations are `github` (this repository, the tag) and, for a distributor tag only,
`modrinth` (version type, loaders, Minecraft versions) and `hangar` (channel, platform
versions, Folia support). Both listings are derived from `SERVER_LEGS` in
`scripts/release-candidate.py`, which a unit test keeps equal to the `Server Boot` matrix in
`ci.yml` and the `Candidate Server Boot` matrix in `release-candidate.yml`: every loader or
platform and every Minecraft version they declare is one the candidate boots on. Today that
is Paper and Folia on `1.21.4`, `1.21.11` and `26.2`, and Paper alone on `26.3`; moving a pin
moves the listings with it. Neither distributor takes a version list per loader, so a
version booted on only one platform is still listed for both: Modrinth names `26.3` beside
the Folia loader, and Hangar lists it among the Paper versions of a project tagged as
supporting Folia, although no Folia 26.3 server is booted. The files are:

* `AntiSpeedrun-<version>.jar`, the plugin;
* `AntiSpeedrun-<version>.jar.sha256`, its checksum line;
* `release-notes.md`, the changelog section used as the release body.

Every stage after the build calls `release-candidate.py verify` on its download. It
recomputes version and channel from the tag rather than trusting the manifest, and checks
every manifest field against the candidate run. The file list must match exactly, with no
extra file, and every digest must match. The JAR must contain exactly one `plugin.yml` and
one `config.yml`, plus the main class. Its packaged `plugin.yml` must declare `name:
AntiSpeedrun`, `version:` equal to the tag without `v`, the expected `main:` and
`folia-supported: true`. The boot test then runs those same downloaded bytes.

**Retention.** The candidate, receipts and evidence are kept for **90 days**, the
repository's maximum, so a single destination can still be retried weeks later. After that
the publisher's plan job stops with the candidate expired, and nothing is rebuilt for that
tag: record every destination still missing under *Not published* below, and the next
release reaches it.

### Step 5: Publish

Start **Publish Release** on the tag, with the candidate run's ID from Step 3:

```bash
gh workflow run release.yml --ref v1.0.0 -f tag=v1.0.0 -f candidate_run_id=<run id>
```

The **Actions → Publish Release → Run workflow** form takes the same inputs; pick the tag,
not a branch, under *Use workflow from*. Every job refuses to run unless the workflow's ref
is the tag given in `tag`.

| Input | Meaning |
| :--- | :--- |
| `tag` | The release tag; the same ref the workflow runs on. |
| `candidate_run_id` | The successful Release Candidate run for this tag. |
| `destinations` | `all` (default): every destination the tag declares (section 2). Or a comma-separated subset, such as `hangar`, to publish or retry only those. |
| `modrinth_absence_confirmed`, `hangar_absence_confirmed` | The absence attestations below. Off by default. |
| `nonce` | Free text appended to the run name, so a script that dispatches the run can find it. |

**Absence attestations.** A Modrinth or Hangar listing is not proof that a version is
absent. Modrinth's version list omits draft and unlisted versions even for the owner's
token, and Hangar can hide soft-deleted versions, whose numbers may still be taken. The
publisher therefore uploads to Modrinth or Hangar only when the maintainer has, for this
dispatch, looked at the destination's owner inventory and set its attestation:

* **Modrinth:** signed in as a project member, open the project's versions in the Modrinth
  web UI, including drafts, unlisted, scheduled and archived versions, and confirm that no
  version numbered exactly the release's version exists in any state.
* **Hangar:** signed in as a project member, open the project's versions in the Hangar web
  UI with every channel and visibility shown, including versions awaiting review, hidden
  and soft-deleted ones, and confirm that none is named exactly the release's version.

An attestation belongs to one dispatch. It is a workflow input and nothing else: never a
variable, a secret or a file. It is needed only for a destination that is not publicly
complete; one that already holds the candidate is verified and skipped without it. The
publisher refuses a true attestation on any attempt of its run other than the first, so
**Re-run** cannot reuse it, and while another publisher run for the same tag is in progress
or finished after this one was created, since that run may have written a version the
inspection did not see. Every job of every publisher run for a tag shares one concurrency
group, `release-publish-<tag ref>`. GitHub needs no attestation: the release API returns
drafts to the publish job's token, and the job checks it directly.

**Plan Publication** needs no approval and no publishing credential, so its failures appear
before anyone is asked to approve. It:

1. checks the tag and the ref, and turns `destinations` into the selected destinations,
   refusing an unknown or repeated name, Modrinth or Hangar for a tag that never goes there,
   and an attestation for a destination that is not selected;
2. accepts the candidate run only when it is a successful tag-push run of
   `release-candidate.yml` in this repository, for this tag and the commit the tag names,
   and pins the newest attempt whose candidate and evidence artifacts are both retained;
3. downloads that candidate and evidence and verifies them, as every later job does again;
4. refuses an attestation on a later attempt or beside an overlapping publisher run;
5. checks each selected destination without credentials: on GitHub, that the tag still
   names the candidate's source and that any release for it matches; on Modrinth and
   Hangar, that the registry knows every listed loader, platform and Minecraft version, and
   what a signed-out consumer sees. A destination that is not publicly complete, with the
   candidate's exact bytes and metadata, fails here unless its attestation is set;
6. summarises the selected destinations, attestations and manifest for approval.

**Approval.** The selected publish jobs, **Publish to GitHub**, **Publish to Modrinth** and
**Publish to Hangar**, all depend on the plan and all wait on the protected `release`
environment, so one approval releases them together. Approve only after reading the plan
summary. Automation never approves it. The environment gate fails open on its own: GitHub
creates a missing environment without protection, and one that lost its reviewer lets the
job start unreviewed. Each publish job's first check, **Require Maintainer Approval**
(`release-publisher.py approval`), reads the run's approvals and continues only if a person,
not a bot or app, approved `release` for this run, and stops on any rejection.

**Each publish job** then, before writing anything:

1. downloads the candidate and evidence again and verifies them;
2. **preflights every selected destination**, not only its own
   (`release-publisher.py preflight`): GitHub as in the plan; for Modrinth, that the token
   and project are set, the token sees the project and its owner is a member who may upload
   versions; for Hangar, the same with the project's Supports Folia tag, the target channel
   and the key's `create_version` permission. A failure in any of them stops every job
   before the first public write, so publishing only some destinations is always a choice
   made in `destinations`. Only the job's own destination is also looked up with the token,
   to find the version absent or exactly the candidate: the jobs start together, so a
   sibling job may already have uploaded to its destination, and nothing that happens there
   after the upload may stop this job.

Only then does each job publish its own destination, independently: a failure in one, such
as a registry outage, does not stop the others. **Publish to GitHub** is the only job with
`contents: write`. `MODRINTH_TOKEN` and `HANGAR_API_TOKEN` are secrets of the `release`
environment; each job passes them only to its preflight step, and only for selected
destinations, and each distributor job passes its own to its publish step.

**Prerequisites only an upload proves.** These cannot be checked without writing. A failure
here is a failure of that destination alone; fix it and retry that destination:

| Destination | Prerequisite |
| :--- | :--- |
| GitHub | The job token may create the release and upload assets for this tag (the #238 failure mode, HTTP 403). |
| Modrinth | `MODRINTH_TOKEN` holds the `VERSION_CREATE` scope; scopes are visible only on Modrinth. |
| Modrinth, Hangar | The registry accepts the JAR itself: file validation, size limits and rate limits. |

**GitHub publication.** `scripts/release-github.py` runs in order:

1. Re-verify the candidate, the manifest, every digest and the evidence. The run's commit
   must equal the candidate's source commit, and the tag on GitHub must still point at it.
   A tag moved after the candidate was built fails the run.
2. Look up every release carrying the tag, drafts included. More than one stops the run.
3. With none, create a **draft** titled `AntiSpeedrun <tag>` with `release-notes.md` as its
   body and the candidate's pre-release flag. It names the existing tag and sends no
   `target_commitish`: with a commit whose workflow files differ from `main`, that field
   appears to make the call need `workflows=write`, which the job's token cannot hold. The
   release's target therefore shows `main`, not the tagged commit, and a tag deleted before
   publishing would be recreated at the head of `main`; step 5 checks the tag before and
   after publishing.
4. Complete the draft. An asset GitHub never finished receiving is deleted; drafts are not
   public. An uploaded asset must match its manifest digest byte for byte. Missing assets
   are uploaded from the candidate.
5. Re-read the draft, check every asset again, re-check the tag, publish, then check the
   tag once more: a tag recreated in between fails the run, with the release already
   public. A pre-release is published with `make_latest=false`. A stable release is published with
   `make_latest=true` only when no published stable release has a higher SemVer version.
   Otherwise it gets `make_latest=false`. This covers re-running an older release's failed
   job, or approving two stable tags out of order. It does not cover two stable publish
   jobs **running at the same time**: the concurrency group is per tag, so both can decide
   before either publishes, and an older release published last can take Latest. Step 6
   catches this and fails the older job, but does not prevent it. Approve one stable
   release at a time.
6. Download each public asset signed out, from the address a user would use, and compare
   it with the manifest. Then check Latest: a stable release with no higher stable release
   published must now be GitHub's Latest, and any other release must not be.

No workflow syncs a Modrinth or Hangar project page. The GitHub Latest marker is the only
shared state the publisher writes.

**Distributor publication.** `scripts/release-distributors.py publish modrinth` and
`publish hangar` each run in order:

1. Refuse a tag that is not a distributor tag, then re-verify the candidate, the manifest,
   every digest and the evidence. The run's commit must equal the candidate's source commit,
   and the tag on `origin` must still point at it.
2. Look the version up with the token, so a version only project members can see still
   counts as present. On Modrinth the candidate JAR's SHA-512 is also looked up across all
   of Modrinth, which finds a version the project listing omits.
3. If it is **absent**, then immediately before uploading: require this dispatch's
   attestation, refuse it on any attempt but the first, and refuse it while another
   publisher run for the tag overlaps this one. Re-running the job alone reuses the plan's
   outputs, which is why these checks sit beside the upload. Then upload the candidate JAR
   as it is: the version number from the tag, the channel from the table in section 2,
   `release-notes.md` as the changelog, and the loaders, platforms and Minecraft versions
   from the manifest. Modrinth versions are named `AntiSpeedrun <version>` and are not
   featured.
4. If it is **present**, or after the upload, compare it with the candidate: version, name,
   channel, visibility, changelog, platforms, Minecraft versions, file name and the digest
   the registry reports. Then fetch the version signed out, download the file a consumer
   would get, and compare its SHA-256 with the manifest. While the registry still hides a
   version that matches, the step reports `awaiting-review` instead and the job's last
   step fails.

Both registries also refuse duplicates on their own: Modrinth rejects a file it already
hosts and Hangar rejects a second version with the same name. A lost upload response
therefore cannot produce a second copy; the next attempt finds the version and verifies it.

**First distributor release.** Neither registry makes a project public before it has a
version. Modrinth reviews a project only once it has one, so `v1.0.0-alpha.1` goes to a
project that is still a draft. A Hangar project stays `new` until its first version is
published, which makes it public. Neither is publicly complete, so the first dispatch needs
both attestations. Preflight checks each project with the token, which sees it in that
state, and accepts a Hangar project that is `new` only while it has no versions. On that
first run the Modrinth version is uploaded and verified with the token (metadata and
SHA-512), but consumers cannot see it yet: **Publish to Modrinth** ends with **Require a
Signed-out Download** failed and Modrinth `awaiting-review`. Submit the Modrinth project for
review. Once it is approved, use **Re-run failed jobs** on that publisher run: the job finds
the version present, needs no attestation, verifies it, and completes the signed-out
download check. The same applies when Hangar holds a version for review (visibility
`needsApproval`). A version awaiting review is not publicly complete, so a new dispatch
would ask for an attestation that cannot honestly be given; re-run the failed job instead.

**Retrying.** Fix the cause first. Then:

| What failed | Retry |
| :--- | :--- |
| The plan: inputs, candidate run, missing attestation, a public mismatch | Fix the inputs and dispatch again. Nothing was approved or written. |
| A preflight, in any job | Fix the secret, variable, project, channel or Folia tag, then dispatch again, or dispatch without that destination to publish the others now. Nothing was written. |
| GitHub publication | **Re-run failed jobs**, or dispatch with `destinations: github`. The script resumes the draft, the uploads or the publication, or only re-verifies a complete release. GitHub needs no attestation. |
| A Modrinth or Hangar upload: outage, upload-only prerequisite | Dispatch with only that destination, after inspecting its owner inventory again and setting its attestation. **Re-run failed jobs** works only if the upload never started: a true attestation is refused on a later attempt. |
| `awaiting-review` | **Re-run failed jobs** on the same run once the registry has approved it (*First distributor release*). |
| The candidate expired (90 days) | Nothing is retried or rebuilt. Record the destination under *Not published*; the next release reaches it. |

A version already present on a registry is verified, never uploaded again, so a retry
writes only to what is still missing. Never push a new candidate for a tag that is already
partly published: its bytes differ and are rejected against what is public.

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
  Modrinth under another version. Compare it with the manifest in the plan summary. If it
  is wrong, delete that version in the registry's web UI, then dispatch that destination
  again with a fresh attestation, which uploads the candidate. If the registry refuses the
  upload after a deletion, cut a new version instead.
* **A distributor reports `awaiting-review`.** The version matches the candidate but is not
  public yet. See *First distributor release*.

**Not published.** A destination a release never reached, because its candidate expired
before it could be retried, is recorded here with the reason. Nothing is rebuilt for it.

| Tag | Destination | Reason |
| :--- | :--- | :--- |
| _none_ | | |

### Maintainer setup

Configured once, in the repository settings in the browser:

* **Environments → `release`**, created before the first tag: required reviewer is the
  maintainer. Deployment branches and tags: **Selected**, with the tag rule `v*`. Without
  the reviewer, every publish job fails its approval check. GitHub publication needs no
  secret. Before the first distributor tag (`v1.0.0-alpha.1`), add two **environment
  secrets** to `release`, never as repository or organisation secrets:
  * `MODRINTH_TOKEN`: a Modrinth personal access token of a project member with upload
    permission, with the scopes `USER_READ`, `PROJECT_READ`, `VERSION_READ` and
    `VERSION_CREATE`.
  * `HANGAR_API_TOKEN`: a Hangar API key of a project member, with the `create_version`
    permission.

  Every publish job reads both, for the preflight of every selected destination.
* **Variables → Repository variables**, read by the plan's signed-out checks and shown in
  its summary:
  * `MODRINTH_PROJECT`: the Modrinth project's slug or ID.
  * `HANGAR_PROJECT`: the Hangar project's slug.

  A distributor tag with any of the four missing fails in the plan or the preflight, before
  anything is published. A development build needs none of them.
* **Modrinth project**: created, with the token's owner as a member who may upload
  versions. It need not be approved yet. Submit it for review after the first distributor
  release uploads its first version. License, icon and description are set in its
  settings; versions carry only what the workflow sends.
* **Hangar project**: created, with **Supports Folia** enabled in its settings, and the
  channels `Alpha`, `Beta` and `Release` present. It may stay `new` until the first
  distributor release publishes its first version. Each version is published for the
  Paper platform.
* **Rules → tag ruleset** (recommended): restrict creating, updating and deleting `v*` tags
  to the maintainer, so nothing else can start or move a release.

---

## 4. Distribution Platforms
* **GitHub Releases** -- Every release: development builds and alphas/betas as
  pre-releases, stable `vX.Y.Z` tags as full releases, through the pipeline in section 3.
* **Modrinth & Hangar** -- From `v1.0.0-alpha.1` onward, on the alpha, beta and release
  channels matching the table in section 2, through their own publish jobs under the same
  approval as GitHub. Never a development build (`v0.Y.Z`, any suffix) or a release
  candidate.
