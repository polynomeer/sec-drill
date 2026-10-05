# api-gateway grader-only material (T13)

Oracle material for the "over-broad API token" incident. `.dockerignore` keeps `private/` out of the Lab image.

- `hidden-tests.json`: supervisor requests and expected outcomes (deny = security, allow = regression)
- `patches/reference`: the reference fix (must be VERIFIED)
- `patches/*`: key mutants that must not be VERIFIED (no-change, reports-implies-all, trust-client-scope, fix-scope-not-revoke, reject-everything, compile-error)
- `hints.json`, `postmortem.json`: learner-facing hints and postmortem questions
