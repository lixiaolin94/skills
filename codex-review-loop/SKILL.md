---
name: codex-review-loop
description: >
    Drive a GitHub pull request through ChatGPT Codex's online PR review to a clean merge:
    trigger a review, poll for the verdict (findings vs clean), fix each finding with local
    verification, and merge only after TWO consecutive clean reviews on the same commit.
    Use when iterating a PR against Codex review comments, or when asked to "run the Codex
    review loop", "handle Codex review findings", or "get this PR clean and merge it".
    Precondition: the repo has ChatGPT Codex's GitHub integration enabled (bot:
    chatgpt-codex-connector[bot]); also needs `gh` (authenticated) and `jq`.
argument-hint: "[PR number, e.g. '6']"
---

# Codex Review Loop

A loop for converging a PR against ChatGPT Codex's **online** GitHub PR review, then merging.
There is no hidden infrastructure — just `gh`, the bundled poll script, and the discipline below.

The mechanical parts (trigger + dual-signal poll + extract findings) are in
[`codex-review-loop.sh`](codex-review-loop.sh). The judgment parts (fixing findings, verifying,
deciding to merge) are yours.

## Precondition (check first)

- The repo must have **ChatGPT Codex's GitHub PR-review integration** enabled (org/repo setting).
  Reviews trigger on PR open, draft→ready, or a `@codex review` comment. Bot account:
  `chatgpt-codex-connector[bot]`. If the repo isn't connected, this loop cannot work — say so.
- `gh` authenticated (`gh auth status`) and `jq` installed. Run the script from inside the repo
  (it auto-detects `owner/name` via `gh repo view`).

## The loop

Run the script in the **background** (it sleeps between polls) so you get re-invoked on completion;
don't foreground-sleep.

1. **Trigger + poll one round.** `bash <skill>/codex-review-loop.sh run <PR>` posts `@codex review`
   (after stamping a UTC baseline) and waits. It prints exactly one verdict line:
   - `RESULT=FORMAL_REVIEW id=<rid> ... inline_comments=<n>` → Codex found issues; the script also
     prints the inline findings (`FILE: path:line` + body).
   - `RESULT=CLEAN ...` / `RESULT=CLEAN_REVIEW ...` → no major issues this round.
   - `RESULT=TIMEOUT` → re-run, or check the PR manually.
2. **If findings:** address EACH one. **Verify Codex's claim against ground truth before fixing —
   it can be wrong** (e.g. it may assert an API shape the code doesn't have; check the source). Fix
   the real issue (and prefer fixing the whole *class*, not just the one instance). Then verify
   locally (compile / unit tests / lint / API gates — whatever the repo uses) **before** pushing.
   Commit, push, and go to step 1 again. A push = new commit = the clean counter resets.
3. **If clean:** that's clean #1. **Re-trigger once more on the same commit** (`run <PR>` again,
   no new commit). Codex is **non-deterministic** — a re-review of the *same* commit can surface a
   new issue (observed in practice). Only after **two consecutive clean** rounds proceed to merge.
4. **Merge** (only with the user's authorization for this repo's merge policy):
   `gh pr merge <PR> --merge --delete-branch`. Then sync local `main`.

## Why the script polls two signals

Codex reports results two different ways, so polling only one misses half the verdicts:
- **Findings** → a formal **review** (`/pulls/<PR>/reviews`, `state=COMMENTED`) plus **inline
  comments**, which correlate to the review via `pull_request_review_id`.
- **Clean** → an **issue comment** (`/issues/<PR>/comments`) whose body says "Didn't find any major
  issues".

Both are filtered by bot login **and** timestamp > the baseline captured at trigger time, so a stale
prior review is never mistaken for the new verdict. A bot "Codex is reviewing…" ack is ignored
because it doesn't match the clean phrasing.

## Discipline that matters (learned the hard way)

- **Two consecutive clean before merge.** Single-clean isn't enough; Codex is non-deterministic.
- **Verify before fixing.** Treat findings as claims to check, not facts. If a claim is wrong for
  this codebase, don't blindly "fix" it (you can make a green gate red). Reply/over-ride with evidence
  or make a robustness improvement that satisfies the underlying concern.
- **Verify before pushing.** Each fix should pass the repo's local gates first — don't burn review
  rounds on compile errors.
- **One concern per round is fine.** Late-stage rounds often surface a single P2/P3; converge them.
- **The merge decision is the user's.** Confirm the merge policy once; don't auto-merge a repo where
  that wasn't authorized.

## Adapting to another repo

- Usually nothing to change — repo is auto-detected. If the bot login or clean phrasing differs,
  edit `BOT` / `CLEAN_RE` at the top of the script.
- Tune `maxIters` / `interval` (args 3 & 4 of `poll`/`run`) for slower review queues.
- Merge strategy: swap `--merge` for `--squash`/`--rebase` per the repo's convention.
