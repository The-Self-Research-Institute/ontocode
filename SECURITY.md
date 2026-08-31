# Security Policy

## Supported Versions

OntoCode is under active development. Security fixes are applied to the latest release on `main`; older tags are not separately maintained.

| Version         | Supported          |
| --------------- | ------------------- |
| Latest (`main`) | :white_check_mark:  |
| Older releases  | :x:                  |

## Reporting a Vulnerability

**Do not open a public GitHub Issue for security vulnerabilities.**

Report security issues privately using [GitHub Security Advisories](https://github.com/The-Self-Research-Institute/ontocode/security/advisories/new) for this repository. This creates a private discussion visible only to you and the maintainers until a fix is ready.

Please include:

- A description of the vulnerability and its potential impact
- Steps to reproduce, or a proof-of-concept
- Affected component (e.g. `ontology-editor`, `ontology-auth`, `ontology-gateway`, `ontology-plugin-service`, `ontology-reasoner-worker`, `ontology-swrl`, `ontology-vscode-extension`, `ontology-desktop`)
- Any known mitigations

## What to Expect

- We will acknowledge your report as soon as possible after triage.
- We will investigate and keep you updated on progress toward a fix.
- Once a fix is available, we will coordinate disclosure timing with you and credit reporters who wish to be credited.
- If a report is declined (e.g. out of scope, not reproducible), we will explain why.

## Scope

This policy covers the OntoCode microservices, VS Code extension, web editor, and desktop application in this repository. Vulnerabilities in third-party dependencies should generally be reported upstream, but please also let us know so we can track and update accordingly.
