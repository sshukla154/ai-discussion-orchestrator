#!/usr/bin/env bash
#
# Fails when a tracked file leaks something that is nobody else's business.
#
# This repository is public, and the project necessarily involves machine-local paths,
# an authenticated CLI, and transcripts of private reasoning. Secret scanners look for
# credential *shapes* -- an API key, a token, a private key -- and have no opinion about
# a home-directory path, a work email address, or a database full of conversation
# history. That gap is what this script covers.
#
# Deliberately shape-based rather than keyword-based: a checker that hardcoded the
# strings it protects would publish them itself. Nothing sensitive appears below.
#
# Usage: scripts/redaction-check.sh
set -uo pipefail

fail=0
self="scripts/redaction-check.sh"

# Everything git tracks, minus this file. Excluding self is what lets the patterns be
# written literally without the checker matching its own source.
mapfile -t tracked < <(git ls-files | grep -v -x -F "$self")

report() {
  printf '\n[FAIL] %s\n' "$1"
  shift
  printf '  %s\n' "$@"
  fail=1
}

scan() {
  local label="$1" pattern="$2"
  local hits
  hits=$(grep -n -I -E "$pattern" -- "${tracked[@]}" 2>/dev/null || true)
  if [ -n "$hits" ]; then
    report "$label" "$hits"
  fi
}

# --- Machine-identifying absolute paths -------------------------------------------
# Any Windows path rooted in a user profile or a personal drive layout. Paths belong in
# configuration or are derived at runtime; see ClaudeCliLocator.
scan "Windows user-profile path in a tracked file" \
     '[A-Za-z]:\\+(Users|sshukla)\\+'
scan "Unix-style mount path to a Windows user profile" \
     '/[a-z]/(Users|sshukla)/'
scan "Expanded home-directory variable baked into a literal" \
     '%(USERPROFILE|HOMEPATH|LOCALAPPDATA)%[\\/]'

# --- Identity ---------------------------------------------------------------------
# Any real email address. The GitHub noreply address used for commit authorship is the
# single permitted form. Done in two stages because ERE has no negative lookahead, and
# depending on grep -P would make this fail on a runner built without PCRE support.
emails=$(grep -n -I -E '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' -- "${tracked[@]}" 2>/dev/null \
         | grep -v 'users\.noreply\.github\.com' || true)
if [ -n "$emails" ]; then
  report "email address in a tracked file" "$emails"
fi

# --- Files that must never be tracked ---------------------------------------------
forbidden=$(git ls-files | grep -E '(^|/)\.claude/|\.db$|\.db-wal$|\.db-shm$|(^|/)\.env$|settings\.local\.json$' || true)
if [ -n "$forbidden" ]; then
  report "file that must never be committed is tracked" "$forbidden"
fi

# --- Workflow secrets -------------------------------------------------------------
# The credential rule is enforceable as a property of the build: nothing here may need a
# repository secret. GITHUB_TOKEN is minted per run by Actions, so it is not one.
if [ -d .github/workflows ]; then
  bad_secrets=$(grep -rn -E 'secrets\.[A-Za-z_]+' .github/workflows 2>/dev/null \
                | grep -v 'secrets\.GITHUB_TOKEN' || true)
  if [ -n "$bad_secrets" ]; then
    report "workflow requires a repository secret" "$bad_secrets"
  fi
fi

if [ "$fail" -ne 0 ]; then
  printf '\nRedaction check failed. Nothing above should be public.\n' >&2
  exit 1
fi

printf 'Redaction check passed (%d tracked files scanned).\n' "${#tracked[@]}"
