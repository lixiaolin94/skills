#!/usr/bin/env bash
# codex-review-loop.sh — drive a GitHub PR through ChatGPT Codex's online review.
#
# Repo is auto-detected via `gh repo view` (run from inside the repo). Requires:
#   - gh (authenticated)         https://cli.github.com
#   - jq
#   - the repo must have ChatGPT Codex's GitHub PR-review integration enabled
#     (bot account: chatgpt-codex-connector[bot]).
#
# Subcommands:
#   trigger  <PR>                              Post "@codex review"; print the baseline UTC timestamp.
#   poll     <PR> <baseline> [maxIters] [sec]  Wait for Codex's verdict submitted AFTER <baseline>.
#   findings <PR> <reviewId>                   Print inline comments (path:line + body) for a review.
#   run      <PR> [maxIters] [sec]             trigger + poll in one shot; print findings if any.
#
# poll/run print exactly one verdict line:
#   RESULT=FORMAL_REVIEW id=<reviewId> state=<state> inline_comments=<n>   # has findings -> fix them
#   RESULT=CLEAN_REVIEW  id=<reviewId> state=<state> inline_comments=0     # review body says clean
#   RESULT=CLEAN         id=<commentUrl>                                   # clean issue-comment verdict
#   RESULT=TIMEOUT       iter=<n>
#
# Defaults: maxIters=60, interval=30s (≈30 min). Codex usually answers in a few minutes.
set -uo pipefail

BOT="chatgpt-codex-connector[bot]"
# Clean-verdict phrasings Codex uses (case-insensitive). Extend if your org's bot differs.
CLEAN_RE="didn.t find any major|no major issues|no issues found|looks good to me"

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)"
[ -n "$REPO" ] || { echo "ERR: not inside a GitHub repo, or gh is not authenticated (try: gh auth status)" >&2; exit 2; }

now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }

cmd_trigger() {
  local pr="${1:?usage: trigger <PR>}"
  local baseline; baseline="$(now_utc)"
  gh pr comment "$pr" --body "@codex review" >/dev/null
  echo "$baseline"
}

cmd_findings() {
  local pr="${1:?usage: findings <PR> <reviewId>}" rid="${2:?usage: findings <PR> <reviewId>}"
  gh api "repos/$REPO/pulls/$pr/comments" --paginate \
    --jq ".[] | select(.pull_request_review_id==$rid) | \"FILE: \(.path):\(.line // .original_line)\n\(.body)\n========\""
}

cmd_poll() {
  local pr="${1:?usage: poll <PR> <baseline>}" baseline="${2:?usage: poll <PR> <baseline>}"
  local maxit="${3:-60}" interval="${4:-30}" i=0
  while (( i < maxit )); do
    i=$((i+1))
    # Signal 1: a NEW formal review by the bot, submitted after the baseline.
    local rev rid state inline clean_body
    rev="$(gh api "repos/$REPO/pulls/$pr/reviews" --paginate 2>/dev/null \
      | jq -c --arg bot "$BOT" --arg base "$baseline" \
        '[.[] | select(.user.login==$bot and .submitted_at > $base)] | sort_by(.submitted_at)')"
    rid="$(printf '%s' "$rev" | jq -r 'last.id // empty')"
    state="$(printf '%s' "$rev" | jq -r 'last.state // empty')"
    if [ -n "$rid" ]; then
      inline="$(gh api "repos/$REPO/pulls/$pr/comments" --paginate 2>/dev/null \
        | jq -r --argjson rid "$rid" '[.[] | select(.pull_request_review_id==$rid)] | length')"
      clean_body="$(printf '%s' "$rev" | jq -r --arg re "$CLEAN_RE" 'last.body // "" | ascii_downcase | test($re)')"
      if [ "${inline:-0}" -gt 0 ]; then
        echo "RESULT=FORMAL_REVIEW id=$rid state=$state inline_comments=$inline"; return 0
      elif [ "$clean_body" = "true" ]; then
        echo "RESULT=CLEAN_REVIEW id=$rid state=$state inline_comments=0"; return 0
      fi
    fi
    # Signal 2: a NEW issue-comment clean verdict by the bot, after the baseline.
    # (Codex reports "clean" via an issue comment, not a formal review — must poll both.)
    local clean_url
    clean_url="$(gh api "repos/$REPO/issues/$pr/comments" --paginate 2>/dev/null \
      | jq -r --arg bot "$BOT" --arg base "$baseline" --arg re "$CLEAN_RE" \
        '[.[] | select(.user.login==$bot and .created_at > $base and ((.body|ascii_downcase)|test($re)))] | last.html_url // empty')"
    [ -n "$clean_url" ] && { echo "RESULT=CLEAN id=$clean_url"; return 0; }
    sleep "$interval"
  done
  echo "RESULT=TIMEOUT iter=$i"
}

cmd_run() {
  local pr="${1:?usage: run <PR>}" maxit="${2:-60}" interval="${3:-30}"
  local baseline; baseline="$(cmd_trigger "$pr")"
  echo "triggered: repo=$REPO pr=$pr baseline=$baseline" >&2
  local out; out="$(cmd_poll "$pr" "$baseline" "$maxit" "$interval")"
  echo "$out"
  if printf '%s' "$out" | grep -q 'RESULT=FORMAL_REVIEW'; then
    local rid; rid="$(printf '%s' "$out" | sed -n 's/.*id=\([0-9][0-9]*\).*/\1/p')"
    [ -n "$rid" ] && { echo "--- inline findings ---"; cmd_findings "$pr" "$rid"; }
  fi
}

sub="${1:-}"; shift || true
case "$sub" in
  trigger)  cmd_trigger "$@" ;;
  poll)     cmd_poll "$@" ;;
  findings) cmd_findings "$@" ;;
  run)      cmd_run "$@" ;;
  *) echo "usage: codex-review-loop.sh {trigger|poll|findings|run} <PR> [...]" >&2; exit 2 ;;
esac
