#!/usr/bin/env bash
set -euo pipefail

base_sha="${1:-}"
head_sha="${2:-HEAD}"
zero_sha="0000000000000000000000000000000000000000"
pattern='^(feat|fix|refactor|perf|test|docs|build|ci|style|chore|revert)(\([a-z0-9._/-]+\))?(!)?: .+'

if [[ -z "$base_sha" || "$base_sha" == "$zero_sha" ]] || ! git cat-file -e "${base_sha}^{commit}" 2>/dev/null; then
    commits=("$head_sha")
else
    mapfile -t commits < <(git rev-list --no-merges "${base_sha}..${head_sha}")
fi

failed=0
for commit in "${commits[@]}"; do
    subject="$(git show -s --format=%s "$commit")"
    if [[ ! "$subject" =~ $pattern ]]; then
        printf 'Invalid commit message: %s  %s\n' "${commit:0:8}" "$subject" >&2
        failed=1
    fi
done

if ((failed)); then
    printf '\nExpected: <type>(<scope>): <description>\n' >&2
    printf 'Allowed types: feat, fix, refactor, perf, test, docs, build, ci, style, chore, revert\n' >&2
    exit 1
fi

printf 'Commit messages follow Conventional Commits.\n'
