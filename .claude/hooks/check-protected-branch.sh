#!/usr/bin/env bash
# PreToolUse hook: block Edit/Write/NotebookEdit on protected branches (develop/main/master).
# Reads the tool call JSON from stdin; only files inside this repo are checked.

set -u

input=$(cat)
file=$(printf '%s' "$input" | jq -r '.tool_input.file_path // .tool_input.notebook_path // empty')
[ -z "$file" ] && exit 0

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
case "$file" in
  "$root"/*) ;;
  *) exit 0 ;;
esac

branch=$(git -C "$root" branch --show-current 2>/dev/null)
case "$branch" in
  develop|main|master)
    jq -n --arg branch "$branch" '{
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "deny",
        permissionDecisionReason: ("当前处于保护分支 " + $branch + "，禁止直接修改代码。请先切换到工作分支再改，例如：git checkout -b feat/xxx（分支与提交规范见 context/team/engineering/01-git.md）。")
      }
    }'
    ;;
esac
exit 0
