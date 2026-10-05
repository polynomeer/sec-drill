# iam-roles grader-only material (T13 Transfer of api-gateway)

Same authorization-boundary concept as api-gateway, a different family: action-level roles on team-owned projects
plus grant revocation. `.dockerignore` keeps `private/` out of the Lab image.

- `hidden-tests.json`: supervisor requests and expected outcomes (deny = security, allow = regression)
- `patches/*`: reference (VERIFIED) and key mutants (no-change, role-implies-all, trust-client-role, fix-role-not-disable, reject-everything, compile-error) that must not be VERIFIED
- `hints.json`, `postmortem.json`
