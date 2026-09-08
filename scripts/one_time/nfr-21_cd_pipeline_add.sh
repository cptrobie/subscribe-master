gh label create "blocked" --color "d93f0b" --description "Cannot be started - waiting on an external decision or dependency" --force

gh issue create \
  --title "[NFR-21] CD pipeline: automated deployment to staging/prod" \
  --label "wave-6" \
  --label "size-L" \
  --label "blocked" \
  --body "See \`subscribe_master_requirements.md\` for the full requirement description, schema-coverage status, and design rationale (search for \`NFR-21\`).

Part of Wave 6 in \`IMPLEMENTATION_BACKLOG.md\` — placed there because Wave 6 completes the \"Waves 0-6\" milestone, the first point where deploying something actually means anything.

**Blocked — do not start.** Prerequisites, none of which exist yet:
1. A chosen hosting platform
2. A \`Dockerfile\` for the application itself (\`docker-compose.yml\` currently only covers local Postgres/Vault infrastructure, not the app)
3. A real (non-dev-mode) Vault deployment with AppRole configured for staging/prod
4. Actual business logic worth deploying

Distinct from NFR-19 (CI), which is unblocked and already closed — CI verifies, CD deploys, and only one of those currently has anywhere to run."