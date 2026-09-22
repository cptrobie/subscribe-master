# Load/performance testing — open questions (not yet a formal NFR)

Real, worth-tracking questions surfaced during FR-02's JWT-vs-opaque-session
discussion. Deliberately not yet a numbered NFR/backlog entry — needs real
scoping, not a rushed table row. Delete this file once that scoping happens
and its content is absorbed into subscribe_master_requirements.md/
IMPLEMENTATION_BACKLOG.md properly.

1. Wave placement is unclear -- Wave 11 fits the "quality gate" framing of its
   neighbors (NFR-06/09/10/11), but load testing needs real functionality to
   be meaningful against -- testing FR-01/FR-02 alone is thin and
   unrepresentative. Possibly: establish tooling in Wave 11, defer actual
   load-test *runs* until Wave 3-8's subscription/payment/scheduling features
   exist.

2. "Establish load testing" alone leaves a gap: who owns what it finds? If a
   real bottleneck surfaces, fixing it is unplanned future work with no
   current home. Needs either an explicit "and file issues for findings"
   clause, or acceptance that this is genuinely open-ended.

3. Observability gap, prerequisite to this being useful at all: nothing in
   this project currently exposes application metrics (no
   Micrometer/Actuator). Load testing without visibility into *why*
   something got slow is close to useless. This might need to be its own
   NFR, ahead of or alongside load testing itself.

4. README's tool list needs two things, not one: something to *generate*
   synthetic load (k6/Gatling/JMeter) and something to *observe* the app
   while under it -- currently neither exists in the documented toolset.

5. JaCoCo interaction, worth resolving deliberately: does a load-test run
   get excluded from coverage reporting (it's not testing correctness)?
   Does running under JaCoCo's instrumentation skew the performance numbers
   being measured?

Revisit once not mid-context-switch on other feature work -- this deserves
real scoping, not a rushed table row.
