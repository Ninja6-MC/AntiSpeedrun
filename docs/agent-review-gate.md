# Decision Record: Ownership of the `ninja6-agent/review` status

> **Status:** Accepted
> **Date:** 2026-09-05
> **Issue:** [#70](https://github.com/Ninja6-MC/AntiSpeedrun/issues/70)
> **Affects:** `.github/workflows/agent-review-gate.yml`, `main` branch protection

---

## Decision

**`.github/workflows/agent-review-gate.yml` keeps ownership of the `ninja6-agent/review`
commit status for the context's whole lifecycle.** It gains a
`pull_request_review: [submitted]` trigger and, when the review author is
`ninja6-agent[bot]`, maps the review verdict onto the status:

| Review state | Status | Meaning |
| :--- | :--- | :--- |
| `APPROVE` | success | Reviewed and cleared |
| `REQUEST_CHANGES` | failure | Reviewed, changes required |
| `COMMENT` | pending | Reviewed, findings still outstanding |

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

## Caveat: base-branch context

`pull_request_review` workflows run in the **base-branch context**. GitHub executes the
copy of `agent-review-gate.yml` that is on `main`, not the copy on the pull request's head
branch.

Two consequences:

- The fix takes effect only for reviews submitted **after** it merges to `main`.
- Changes to the `resolve` job cannot be exercised on the pull request that introduces
  them. Verification happens on the next pull request reviewed after the merge.

Pull requests already open when this merges keep whatever status they were left with;
their next review submission resolves it.
