# Decision Record: Ownership of the `ninja6-agent/review` status

> **Status:** Accepted
> **Date:** 2026-09-05
> **Issue:** [#70](https://github.com/Ninja6-MC/AntiSpeedrun/issues/70)
> **Affects:** `.github/workflows/agent-review-gate.yml`, `main` branch protection

---

## Decision

**`.github/workflows/agent-review-gate.yml` keeps ownership of the `ninja6-agent/review`
commit status for the context's whole lifecycle on same-repository pull requests.** It
gains a `pull_request_review: [submitted, dismissed]` trigger and, when the review author
is `ninja6-agent[bot]`, maps the review verdict onto the status:

| Review state | Status | Meaning |
| :--- | :--- | :--- |
| `APPROVE` | success | Reviewed and cleared |
| `REQUEST_CHANGES` | failure | Reviewed, changes required |
| `COMMENT` | pending | Reviewed, findings still outstanding |
| Dismissed | from the latest standing review on the head, else pending | Reviewed, no longer cleared |

A dismissal re-derives the status rather than forcing it to pending, because the review
dismissed may not be the one the status came from: dismissing an old failing review
beneath a newer approval must leave the approval standing. With no `ninja6-agent[bot]`
review left standing on the head commit, the status is pending (issue #85).

**Fork pull requests get nothing from the workflow.** GitHub gives them a read-only
`GITHUB_TOKEN`, so any status write would fail with 403 and show the contributor a red
check they cannot act on. Both jobs are therefore skipped unless the head repository is
this one. On a fork pull request the context appears only if the reviewer's own status
call writes it with the App's token; the context is not required, so its absence blocks
nothing.

`agent/tools/review-as-bot.mjs status` continues to write the same context as the
reviewer's own last step. **The two are deliberately not exclusive.** The tool is the
fast path; the workflow is the safety net for a reviewer that dies between posting its
review and setting the status. Both derive the state from the same review, so they
cannot disagree — the later write repeats the earlier one.

---

## Do not make this a required status check

**`ninja6-agent/review` must never be added to `main`'s required status checks.** The
required contexts are, and should remain:

```
dco / Check Sign-off
standards / Check Standards
Build and Test
```

A green tick on `ninja6-agent/review` means *"an independent agent reviewer approved"*,
never *"a human approved"*. Reviewers are spawned with no prior context, so the author of
a change writes no part of the reviewer's prompt. That narrows the failure mode, but it
is still the same system certifying its own work. Promoting the context to required would
make an agent's approval sufficient to merge, with no human in the loop — which is the
opposite of what a review gate is for.

This paragraph is duplicated as a comment block at the top of the workflow file, because
that is the other place someone reaches for when they are about to edit branch
protection.

---

## Context

The workflow as written fired only on `pull_request: [opened, synchronize, reopened]`, and
its only non-exempt branch posted `state=pending`. Nothing in the repository ever wrote
that context again, so every human-authored pull request carried a status that could not
resolve — observed on [#64](https://github.com/Ninja6-MC/AntiSpeedrun/pull/64),
[#66](https://github.com/Ninja6-MC/AntiSpeedrun/pull/66) and
[#67](https://github.com/Ninja6-MC/AntiSpeedrun/pull/67), all of which sat at
`pending / Waiting for ninja6-agent review` through review, fixes and merge.

That was cosmetic only because the context is not required. It stops being cosmetic the
moment someone adds it to the required list — the obvious thing to do with a gate that
exists — at which point `main` becomes unmergeable and the cause is a workflow that looks
like it is working.

Separately, the `ninja6-agent` App gained `statuses: write` on 2026-09-04, and
`review-as-bot.mjs status` began writing the same context. Two mechanisms then had a
claim on it, and the overlap is what needed resolving, rather than either half alone.

---

## Why this option, and not the other two

Three options were on the table.

**1. The workflow owns it (chosen).** Self-contained: no App permission is involved, and
the workflow's `GITHUB_TOKEN` already declares `statuses: write`. It resolves the context
from the review event itself, so the status cannot outlive the review that justifies it.

**2. The bot owns it, workflow stays a pending-registrar.** This is what happens today,
and it works — but only while the reviewer completes. A reviewer that dies after posting
its review and before its `status` call leaves the context pending with no mechanism to
clear it, which is the original defect in a narrower form. Rejected as the *sole*
mechanism, retained as the fast path.

**3. Drop the gate entirely.** The status has never gated anything, and a review posted
as `ninja6-agent[bot]` is visible on the pull request without it. Rejected: the status is
the one machine-readable signal of whether a change was reviewed at all, and it is
cheaper to fix a two-job workflow than to reconstruct that signal later.

Options 1 and 2 are not exclusive; 1 is the safety net for 2.

---

## Which copy of this workflow runs

Two questions are easy to conflate here. GitHub documents which **ref and SHA** a
`pull_request_review` run executes against. Which copy of the workflow **definition** is
loaded for that run is a separate question. This decision record originally assumed the
definition on `main` would be used, and drew two consequences from it — that the fix could
only take effect after merging, and that it could not be exercised on the pull request
introducing it.

**Both were contradicted by the first review this gate handled.** When the reviewer
approved that pull request, GitHub ran a `pull_request_review` workflow at head `4191386`
whose jobs were `Register Review Status` and `Resolve Review Status`. Those job names
existed only on the head branch — `main` carried a single job named `gate` and no
`pull_request_review` trigger at all — so that definition cannot have come from `main`.
The head branch's definition ran, and it resolved `ninja6-agent/review` to `success`
correctly.

What that does and does not establish:

- It establishes that, for a same-repository pull request, the head branch's definition of
  this workflow ran on `pull_request_review`, and that the `resolve` job worked before
  merging.
- It says nothing about which ref the run executed against, and does **not** contradict
  GitHub's documentation of that.
- Fork pull requests were **not** tested, and both jobs are now skipped for them (see the
  decision above).

When changing the `resolve` job, check which jobs a run actually executed rather than
assuming either rule.

Pull requests already open when this merges keep whatever status they were left with;
their next review submission resolves it.
