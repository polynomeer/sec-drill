# tenant-orders grader-only material

Oracle material for patch grading (T08, ADR 0009). It goes into the private part of the content bundle only.
`.dockerignore` keeps it out of the Lab image; nothing here may reach a Lab, a learner response or a log.

- `hidden-tests.json`: requests the grading supervisor sends to the patched app, with the expected outcome
- `patches/reference`: the reference fix (must be VERIFIED)
- `patches/*`: mutants and bad patches that must not be VERIFIED (see the oracle `mutants` list)
