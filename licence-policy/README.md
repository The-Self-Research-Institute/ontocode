# Licence policy

`policy.json` is the single rule for every library we ship (Maven, npm and the final jars).

- `allowed`: SPDX ids that are fine.
- `allowedByName`: LGPL libraries accepted one by one (`group:artifact`). Any other LGPL library fails.
- `blocked`: GPL, AGPL, SSPL, unknown licences, banned names (Openllet, Pellet) and banned class prefixes.
- `curations`: a wrong or missing declared licence, overridden with an evidence link and a reviewer.
- `baseline`: existing unknowns accepted for now, each with a reason. Blocked licences can never be baselined.

A library with several licences passes if any one of them is allowed.

## Run

    python scripts/licences/check_maven.py            # runs mvn dependency:list offline, then applies the policy
    python scripts/licences/check_maven.py --skip-run # reuse target/licence-deplist.txt files
    python scripts/licences/check_npm.py              # production dependencies; add --all for dev
    python scripts/licences/scan_artifacts.py a.jar folder/ --report out.json
    python scripts/licences/scan_artifacts.py --self-test

Each command exits non-zero on a blocked or unknown licence. CI: `.github/workflows/licence-check.yml`.
Maven modules are covered by path, including `ontology-desktop`, which does not inherit the root pom.
