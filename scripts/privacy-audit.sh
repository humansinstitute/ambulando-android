#!/bin/zsh
# Privacy and secret audit for the public repository and the release APK.
#
#   scripts/privacy-audit.sh [path/to/app-release.apk]
#
# Scans (1) every tracked file, (2) the whole Git history of every branch (added lines and commit metadata), and
# (3) optionally an APK (file list, and text found inside every entry). It looks for signing material, private keys,
# Nostr secret keys, credentials, local paths, emails and any extra patterns listed one per line (extended regex) in
# the git-ignored file tmp/privacy-denylist.txt — private hosts, record ids and names belong there, never in this script.
#
# Allowed on purpose: the public NIP-19 specification test vector, the publisher npub in zapstore.yaml, the GitHub
# noreply commit address and example/invalid hosts. Exit status 1 if anything else matches.
set -uo pipefail
cd "$(dirname "$0")/.."

apk="${1:-}"
denylist="tmp/privacy-denylist.txt"
report="$(mktemp -t privacy-audit)"
failures=0

generic=(
  'nsec1[02-9ac-hj-np-z]{20,}'
  'BEGIN (RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY'
  '(storePassword|keyPassword)[[:space:]]*=[[:space:]]*"'
  'STORE_PASSWORD=["'"'"']?[A-Za-z0-9]'
  '(api|secret|access)[_-]?(key|token)["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"'][A-Za-z0-9_\-]{16,}'
  'gh[pousr]_[A-Za-z0-9]{30,}'
  'bunker[:]//'
  '/Users/[a-z]'
  '/home/[a-z]+/'
  '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.(com|net|org|io|studio|ai|dev)'
)
allowed='npub1llwrq3rtah3rg3r2dyfyht55ek7aa0ey7z47ujju407pzfp38shqa7zcvr|nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5|users\.noreply\.github\.com|@([a-z0-9-]+\.)*example\.(com|org)|\.invalid|noreply@'

# Upstream copyright notices must retain their published attribution. This narrow allowlist is reviewed with the
# locked dependencies; private development author metadata is never allowlisted.
if [[ -f scripts/public-attributions.txt ]]; then
  while IFS= read -r attribution; do
    [[ -z "$attribution" || "$attribution" == \#* ]] && continue
    escaped="$(print -r -- "$attribution" | sed 's/[][\\.^$*+?(){}|]/\\&/g')"
    allowed+="|$escaped"
  done < scripts/public-attributions.txt
fi

patterns=("${generic[@]}")
if [[ -f "$denylist" ]]; then
  while IFS= read -r line; do [[ -n "$line" && "$line" != \#* ]] && patterns+=("$line"); done < "$denylist"
else
  print "warning: $denylist not found; only generic patterns are checked" >&2
fi
joined="${(j:|:)patterns}"

check() {  # label, input stream on stdin
  local label="$1" hits
  hits="$(grep -nEI "$joined" | grep -vE "$allowed" | grep -v 'scripts/privacy-audit.sh' || true)"
  if [[ -n "$hits" ]]; then
    print "== $label" >> "$report"; print -r -- "$hits" | head -50 >> "$report"
    failures=$((failures + 1))
  fi
}

# 1. Tracked files (and forbidden file types).
forbidden="$(git ls-files | grep -E '\.(jks|keystore|p12|pfx|pem|key|apk|aab|idsig)$|(^|/)(local\.properties|keystore\.properties|\.env(\..*)?|google-services\.json)$' | grep -v '\.env\.example$' || true)"
[[ -n "$forbidden" ]] && { print "== forbidden tracked files" >> "$report"; print -r -- "$forbidden" >> "$report"; failures=$((failures + 1)); }
git ls-files -z | xargs -0 grep -nEI "$joined" 2>/dev/null | grep -vE "$allowed" | grep -v '^scripts/privacy-audit.sh:' > "$report.tree" || true
if [[ -s "$report.tree" ]]; then print "== tracked files" >> "$report"; head -80 "$report.tree" >> "$report"; failures=$((failures + 1)); fi

# 2. History: every added line and every commit's author/committer/message, on all branches.
git log --all -p --no-color --format='commit %H%nAuthor: %an <%ae>%nCommitter: %cn <%ce>%n%B' | grep -E '^(\+[^+]|commit |Author:|Committer:)|^[^-+@ ]' | check "git history"
deleted="$(git log --all --diff-filter=D --name-only --format= | grep -E '\.(jks|keystore|p12|pem|key)$|local\.properties|\.env' || true)"
[[ -n "$deleted" ]] && { print "== sensitive files present earlier in history" >> "$report"; print -r -- "$deleted" >> "$report"; failures=$((failures + 1)); }

# 3. APK contents.
if [[ -n "$apk" ]]; then
  [[ -f "$apk" ]] || { print "APK not found: $apk" >&2; exit 2; }
  scratch="$(mktemp -d -t apk-audit)"
  unzip -qq -o "$apk" -d "$scratch"
  (cd "$scratch" && find . -type f | sed 's|^\./||') | grep -E '\.(jks|keystore|p12|pem|key|properties|env)$' | check "APK file names"
  (cd "$scratch" && find . -type f -print0 | xargs -0 strings -n 6 2>/dev/null) | check "APK contents"
fi

if (( failures > 0 )); then
  print "Privacy audit FAILED ($failures area(s)). Findings:" >&2
  cat "$report" >&2
  exit 1
fi
print "Privacy audit passed: tracked files, full history${apk:+ and $apk} contain no matches."
