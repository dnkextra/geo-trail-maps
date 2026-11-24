#!/bin/sh
# Shared by the hooks: reads text on stdin and fails if it holds an email address
# that isn't a GitHub noreply or Anthropic noreply address. Using an allowlist keeps
# any real address out of this public file.
bad=$(grep -oE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]+' |
    grep -vE '@users\.noreply\.github\.com$|^noreply@anthropic\.com$' | sort -u)
if [ -n "$bad" ]; then
    echo "Blocked: $1 contains a non-noreply email address:" >&2
    echo "$bad" | sed 's/^/  /' >&2
    exit 1
fi
