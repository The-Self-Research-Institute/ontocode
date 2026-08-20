# Contributing to OntoCode

OntoCode is a **public** repository: anyone can read the code, fork it, and open a pull request. That does not mean write access is open.

**Public visibility ≠ write access.** You cannot fully block forks/PRs on a public repo. You *can* block unsolicited pushes/merges and require approval before someone becomes a real contributor.

## Request to contribute

Before large work (or if you need push access):

1. Open an issue titled `Request to contribute` (or email maintainers if Issues are disabled).
2. Say what you want to work on and whether you need collaborator (write) access.
3. Wait for maintainer approval.
4. Then **fork → branch → PR** (usual path), or accept a collaborator invite if granted for ongoing work.

Small fixes (typos, obvious bugs) via fork + PR are fine without a formal request. Large unsolicited PRs may be deferred until approved.

## Pull request process

1. Fork (or use your collaborator clone if invited).
2. Branch from `main`.
3. Keep changes focused; match existing style.
4. Open a PR against `main`.
5. Address review comments. Only maintainers merge.

## Access model

| Action | Public (no invite) | Collaborator | Maintainer |
|--------|--------------------|--------------|------------|
| Read / fork | Yes | Yes | Yes |
| Open a PR from a fork | Yes | Yes | Yes |
| Push to this repo | No | Yes | Yes |
| Merge to `main` | No | No | Yes |

## Reporting issues

Use GitHub Issues for bugs/features, or in-app **Help → Report Issue** where available. For security-sensitive reports, contact maintainers privately.

---

## How maintainers protect this repo (correct setup)

Do these in order on [github.com/The-Self-Research-Institute/ontocode](https://github.com/The-Self-Research-Institute/ontocode).

### 1. Org team for ownership (stable — usernames can change)

Individual `@username` entries in CODEOWNERS break if that person renames. Use an **org team** instead.

1. Org → **Teams** → New team: `ontocode-maintainers` (visibility: Visible or Secret).
2. Add trusted maintainers to the team (e.g. your account).
3. Give the team **Maintain** (or Admin) on this repo: Repo → **Settings** → **Collaborators and teams** → add team with role **Maintain**.
4. Keep [`.github/CODEOWNERS`](.github/CODEOWNERS) as:

   ```
   * @The-Self-Research-Institute/ontocode-maintainers
   ```

### 2. Do not grant random write access

- Leave the repo **public** if you want visibility.
- **Collaborators**: invite **Write** only after an approved contribution request (ongoing contributors). Prefer fork + PR for one-offs.
- Never rely on “security through obscurity”; rely on rulesets + who has Write/Maintain.

### 3. Protect `main` with a ruleset

Repo → **Settings** → **Rules** → **Rulesets** → **New branch ruleset**:

| Setting | Value |
|---------|--------|
| Ruleset name | `Protect main` |
| Enforcement | Active |
| Target branches | `main` (include by name, or `~DEFAULT_BRANCH`) |
| Restrict deletions | On |
| Block force pushes | On |
| Require a pull request before merging | On |
| Required approvals | `1` (or more) |
| Dismiss stale pull request approvals when new commits are pushed | On |
| Require conversation resolution before merging | On |
| Require review from Code Owners | On |
| Require status checks to pass | On (once CI is reliable; add your required job names) |
| Require branches to be up to date before merging | Recommended with status checks |
| Restrict who can push / bypass | Only org admins / the maintainers team; avoid broad bypass |

Save the ruleset.

Legacy path (same idea): **Settings** → **Branches** → branch protection rule for `main`. Prefer **Rulesets** on orgs.

### 4. Optional hardening

- **Restrict who can create matching branches** / push to matching branches so only the maintainers team can push branch names you care about.
- Disable or limit **Allow force pushes** / **Allow deletions** everywhere for protected refs.
- If you do not want public tickets: disable Issues; use Discussions or email for contribution requests.
- For a **hard** lock (no public PRs on the real codebase): make the working repo **private**, or keep a public read-only mirror and develop in private.

### 5. What this does *not* stop

On a public repo, strangers can still **fork and open PRs**. That is expected. Protection means: they cannot push to your repo, cannot merge to `main`, and cannot become collaborators without your invite.

### Checklist

- [ ] Team `ontocode-maintainers` exists; maintainers are members
- [ ] Team has Maintain on this repo
- [ ] `.github/CODEOWNERS` points at that team
- [ ] Ruleset on `main`: PR required, 1+ approvals, conversation resolution, no force-push/delete, Code Owners required
- [ ] Status checks required (when CI is green and stable)
- [ ] No casual Write invites; request-to-contribute process documented above
