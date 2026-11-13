# Capacity profiles

One file per server size describes how much memory, how many connections and how much disk each part of the system may use. The checker compares the profile with the real compose files and fails when the numbers don't fit.

## Run

```
node scripts/capacity/check-profile.mjs r6i-xlarge
node scripts/capacity/check-profile.mjs r6i-xlarge --json
```

`--strict` also fails on warnings. The exit code is 1 when a rule fails.

## Rules

| Rule | Meaning |
|---|---|
| `limits-fit-host` | Container limits add up to at most 85% of the host RAM. |
| `heap-below-limit` | A Java heap must be smaller than its container limit. |
| `jvm-headroom` | Memory outside the heap (stacks, metaspace, direct buffers) must be at least 384 MB plus 5% of the heap, plus any `extraNativeMb`. |
| `db-connections` | Connection pools must fit within the database's connection limit after a reserve. |
| `disk-fixed-caps` | Items with a cap (logs, dumps) must use at most half the disk. |
| `disk-unbounded` | Lists everything that has no cap yet. |
| `compose-drift` | The profile must match the memory limits and `-Xmx` values in the compose files. |

## Add a setting

Add the value to the profile, then add a rule or a field in `scripts/capacity/check-profile.mjs`. Keep one profile per server size.
