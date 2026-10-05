# tenant-invoices grader-only material (T13 Transfer of tenant-orders)

Same object-level authorization concept as tenant-orders, a different family (invoices, UUID paths). `.dockerignore`
keeps `private/` out of the Lab image.

- `hidden-tests.json`: supervisor requests and expected outcomes (deny = security, allow = regression)
- `patches/reference`: the reference fix (must be VERIFIED)
- `patches/*`: key mutants that must not be VERIFIED (no-change, fix-only-direct-route, trust-client-tenant, deny-everything, compile-error)
- `hints.json`, `postmortem.json`: learner-facing hints and postmortem questions
