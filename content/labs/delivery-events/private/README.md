# delivery-events grader-only material (T13 Transfer of webhook-receiver)

Same idempotency concept as webhook-receiver plus event ordering, a different family. `.dockerignore` keeps
`private/` out of the Lab image.

- `hidden-tests.json`: supervisor requests and expected outcomes (deny = security, allow = regression)
- `patches/*`: reference (VERIFIED) and key mutants (no-change, idempotent-only, freshness-only, reject-everything, compile-error) that must not be VERIFIED
- `hints.json`, `postmortem.json`
