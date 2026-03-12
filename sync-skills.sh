#!/bin/bash
# Sync skill directories to ~/.claude/skills and ~/.agents/skills
# Usage: ./sync-skills.sh [--dry-run]

SKILLS_DIR="$(cd "$(dirname "$0")" && pwd)"
CLAUDE_DIR="$HOME/.claude/skills"
AGENTS_DIR="$HOME/.agents/skills"
DRY_RUN=false

[[ "$1" == "--dry-run" ]] && DRY_RUN=true

link_skill() {
  local src="$1" target_dir="$2" name="$3"
  local target="$target_dir/$name"

  mkdir -p "$target_dir"

  if [ -L "$target" ]; then
    local current
    current="$(readlink "$target")"
    if [ "$current" = "$src" ]; then
      echo "  ✓ $target (already linked)"
      return
    fi
    # Symlink exists but points elsewhere — overwrite
    if $DRY_RUN; then
      echo "  → $target (would replace: $current)"
    else
      ln -sfn "$src" "$target"
      echo "  → $target (replaced: $current)"
    fi
  elif [ -e "$target" ]; then
    # Real file/directory — warn, don't destroy
    echo "  ⚠ $target exists as real directory, skipped (remove manually to link)"
  else
    if $DRY_RUN; then
      echo "  → $target (would create)"
    else
      ln -sfn "$src" "$target"
      echo "  → $target (created)"
    fi
  fi
}

$DRY_RUN && echo "[dry-run mode]" && echo

count=0
for dir in "$SKILLS_DIR"/*/; do
  [ -d "$dir" ] || continue
  name="$(basename "$dir")"
  [[ "$name" == .* ]] && continue  # skip hidden dirs

  echo "$name"
  link_skill "$SKILLS_DIR/$name" "$CLAUDE_DIR" "$name"
  link_skill "$SKILLS_DIR/$name" "$AGENTS_DIR" "$name"
  echo
  ((count++))
done

echo "Synced $count skill(s)."
