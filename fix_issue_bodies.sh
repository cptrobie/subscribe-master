#!/usr/bin/env bash
set -euo pipefail

# =====================================================================
# Fixes issue bodies for #1-#50, created by the original
# create_github_issues.sh, which had two bugs:
#   1. Backtick-wrapped text (e.g. `subscribe_master_requirements.md`)
#      was embedded in DOUBLE-quoted --body arguments, which do NOT
#      neutralize backticks in bash -- this triggered command
#      substitution, silently stripping every backtick-wrapped
#      reference (replaced with the empty output of a failed command).
#   2. Body lines were joined with a literal two-character '\n'
#      string instead of a real newline, so line breaks rendered as
#      literal text instead of actual paragraph breaks.
#
# This script uses single-quoted --body arguments instead, which fully
# neutralize backticks in bash, and real embedded newlines.
#
# Usage: run from inside the repo, with gh already authenticated.
#   chmod +x fix_issue_bodies.sh && ./fix_issue_bodies.sh
# =====================================================================

echo "Fixing 50 issue bodies..."

echo 'Fixing #1 (NFR-13)...'
gh issue edit 1 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-13`).

Part of Wave 0 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #2 (NFR-12)...'
gh issue edit 2 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-12`).

Part of Wave 0 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #3 (NFR-08)...'
gh issue edit 3 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-08`).

Part of Wave 0 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #4 (NFR-20)...'
gh issue edit 4 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-20`).

Part of Wave 0 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #5 (NFR-19)...'
gh issue edit 5 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-19`).

Part of Wave 0 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #6 (NFR-09)...'
gh issue edit 6 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-09`).

Part of Wave 0 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #7 (NFR-01)...'
gh issue edit 7 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-01`).

Part of Wave 0 in `IMPLEMENTATION_BACKLOG.md`.

**Standing convention** -- this isn'\''t a one-time feature; it'\''s enforced continuously across future PRs (e.g. via code review checklist or a linter rule), not something that gets "done" once and forgotten. See `IMPLEMENTATION_BACKLOG.md` for the full explanation of this distinction.'

echo 'Fixing #8 (NFR-14)...'
gh issue edit 8 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-14`).

Part of Wave 0 in `IMPLEMENTATION_BACKLOG.md`.

**Standing convention** -- this isn'\''t a one-time feature; it'\''s enforced continuously across future PRs (e.g. via code review checklist or a linter rule), not something that gets "done" once and forgotten. See `IMPLEMENTATION_BACKLOG.md` for the full explanation of this distinction.'

echo 'Fixing #9 (NFR-15)...'
gh issue edit 9 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-15`).

Part of Wave 0 in `IMPLEMENTATION_BACKLOG.md`.

**Standing convention** -- this isn'\''t a one-time feature; it'\''s enforced continuously across future PRs (e.g. via code review checklist or a linter rule), not something that gets "done" once and forgotten. See `IMPLEMENTATION_BACKLOG.md` for the full explanation of this distinction.'

echo 'Fixing #10 (NFR-16)...'
gh issue edit 10 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-16`).

Part of Wave 0 in `IMPLEMENTATION_BACKLOG.md`.

**Standing convention** -- this isn'\''t a one-time feature; it'\''s enforced continuously across future PRs (e.g. via code review checklist or a linter rule), not something that gets "done" once and forgotten. See `IMPLEMENTATION_BACKLOG.md` for the full explanation of this distinction.'

echo 'Fixing #11 (FR-01)...'
gh issue edit 11 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-01`).

Part of Wave 1 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #12 (FR-02)...'
gh issue edit 12 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-02`).

Part of Wave 1 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #13 (FR-03)...'
gh issue edit 13 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-03`).

Part of Wave 1 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #14 (FR-04)...'
gh issue edit 14 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-04`).

Part of Wave 1 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #15 (NFR-02)...'
gh issue edit 15 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-02`).

Part of Wave 1 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #16 (FR-06)...'
gh issue edit 16 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-06`).

Part of Wave 2 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #17 (FR-05)...'
gh issue edit 17 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-05`).

Part of Wave 2 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #18 (FR-07)...'
gh issue edit 18 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-07`).

Part of Wave 3 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #19 (FR-08)...'
gh issue edit 19 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-08`).

Part of Wave 3 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #20 (FR-09)...'
gh issue edit 20 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-09`).

Part of Wave 3 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #21 (NFR-04)...'
gh issue edit 21 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-04`).

Part of Wave 3 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #22 (FR-10)...'
gh issue edit 22 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-10`).

Part of Wave 4 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #23 (FR-11)...'
gh issue edit 23 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-11`).

Part of Wave 4 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #24 (FR-27)...'
gh issue edit 24 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-27`).

Part of Wave 4 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #25 (FR-13)...'
gh issue edit 25 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-13`).

Part of Wave 5 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #26 (FR-14)...'
gh issue edit 26 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-14`).

Part of Wave 5 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #27 (FR-15)...'
gh issue edit 27 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-15`).

Part of Wave 5 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #28 (NFR-05)...'
gh issue edit 28 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-05`).

Part of Wave 5 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #29 (FR-17)...'
gh issue edit 29 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-17`).

Part of Wave 5 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #30 (FR-16)...'
gh issue edit 30 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-16`).

Part of Wave 5 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #31 (FR-12)...'
gh issue edit 31 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-12`).

Part of Wave 6 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #32 (NFR-03)...'
gh issue edit 32 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-03`).

Part of Wave 6 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #33 (FR-29)...'
gh issue edit 33 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-29`).

Part of Wave 7 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #34 (FR-28)...'
gh issue edit 34 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-28`).

Part of Wave 7 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #35 (FR-18)...'
gh issue edit 35 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-18`).

Part of Wave 8 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #36 (FR-19)...'
gh issue edit 36 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-19`).

Part of Wave 8 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #37 (FR-20)...'
gh issue edit 37 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-20`).

Part of Wave 8 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #38 (FR-21)...'
gh issue edit 38 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-21`).

Part of Wave 8 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #39 (FR-22)...'
gh issue edit 39 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-22`).

Part of Wave 9 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #40 (FR-23)...'
gh issue edit 40 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-23`).

Part of Wave 9 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #41 (FR-24)...'
gh issue edit 41 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-24`).

Part of Wave 9 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #42 (FR-25)...'
gh issue edit 42 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-25`).

Part of Wave 9 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #43 (FR-26)...'
gh issue edit 43 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `FR-26`).

Part of Wave 9 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #44 (NFR-07)...'
gh issue edit 44 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-07`).

Part of Wave 10 in `IMPLEMENTATION_BACKLOG.md`.

**Standing convention** -- this isn'\''t a one-time feature; it'\''s enforced continuously across future PRs (e.g. via code review checklist or a linter rule), not something that gets "done" once and forgotten. See `IMPLEMENTATION_BACKLOG.md` for the full explanation of this distinction.'

echo 'Fixing #45 (NFR-17)...'
gh issue edit 45 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-17`).

Part of Wave 10 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #46 (NFR-18)...'
gh issue edit 46 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-18`).

Part of Wave 10 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #47 (NFR-06)...'
gh issue edit 47 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-06`).

Part of Wave 11 in `IMPLEMENTATION_BACKLOG.md`.

**Standing convention** -- this isn'\''t a one-time feature; it'\''s enforced continuously across future PRs (e.g. via code review checklist or a linter rule), not something that gets "done" once and forgotten. See `IMPLEMENTATION_BACKLOG.md` for the full explanation of this distinction.'

echo 'Fixing #48 (NFR-10)...'
gh issue edit 48 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-10`).

Part of Wave 11 in `IMPLEMENTATION_BACKLOG.md`.

**Standing convention** -- this isn'\''t a one-time feature; it'\''s enforced continuously across future PRs (e.g. via code review checklist or a linter rule), not something that gets "done" once and forgotten. See `IMPLEMENTATION_BACKLOG.md` for the full explanation of this distinction.'

echo 'Fixing #49 (NFR-11)...'
gh issue edit 49 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-11`).

Part of Wave 11 in `IMPLEMENTATION_BACKLOG.md`.'

echo 'Fixing #50 (NFR-09)...'
gh issue edit 50 --body 'See `subscribe_master_requirements.md` for the full requirement description, schema-coverage status, and design rationale (search for `NFR-09`).

Part of Wave 11 in `IMPLEMENTATION_BACKLOG.md`.

**Standing convention** -- this isn'\''t a one-time feature; it'\''s enforced continuously across future PRs (e.g. via code review checklist or a linter rule), not something that gets "done" once and forgotten. See `IMPLEMENTATION_BACKLOG.md` for the full explanation of this distinction.'

echo "Done. Spot-check a few issues to confirm formatting looks correct."
