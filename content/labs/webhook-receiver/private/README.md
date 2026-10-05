# webhook-receiver grader-only material (T13)

Oracle material for the "webhook replay" incident. `.dockerignore` keeps `private/` out of the Lab image.

- `hidden-tests.json`: supervisor requests and expected outcomes (deny = security, allow = regression)
- `patches/reference`: the reference fix (must be VERIFIED)
- `patches/*`: key mutants that must not be VERIFIED (no-change, dedup-only, freshness-only, reject-everything, dedup-by-amount, tamper-framework, compile-error)
- `hints.json`, `postmortem.json`: learner-facing hints and postmortem questions
