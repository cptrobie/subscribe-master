gh issue create \
  --title "[NFR-32] Operational dashboards and alerting (Grafana)" \
  --label "wave-10" \
  --label "size-M" \
  --body "See \`subscribe_master_requirements.md\` for the full requirement description, schema-coverage status, and design rationale (search for \`NFR-32\`).

Part of Wave 10 in \`IMPLEMENTATION_BACKLOG.md\`. Depends on \`NFR-17\`/\`NFR-18\` existing first — dashboards need data to show.

Turns \`ARCHITECTURE.md\` §11's ops/support/audit monitoring guide (11 conditions currently requiring manual SQL) into actual dashboards and alert rules. See \`ARCHITECTURE.md\` §6.5 for the deliberate scope boundary — Grafana's built-in PostgreSQL data source against the existing schema, no Loki/Tempo/separate metrics store introduced yet."
