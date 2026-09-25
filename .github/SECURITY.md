# Security policy

## Supported versions

Keyrook is maintained by a single person.

| Version | Supported |
| --- | --- |
| Latest 1.x minor release | Yes |
| Earlier 1.x minor releases | No |
| 0.x | No |

- Security fixes are made only for the latest 1.x minor release and published
  as a new patch release of it. Earlier minor releases do not receive
  backports; update to the latest one.
- The 0.x releases are unsupported from 1.0.0 on. Their vaults open in every
  1.x release (see the
  [compatibility promise](../docs/FORMAT.md#compatibility-promise-from-100)).
- Every installer bundles its own Java runtime. Security updates of that
  runtime are shipped as patch releases (see
  [bundled JDK security updates](../docs/PACKAGING.md#bundled-jdk-security-updates)).

Update to the
[latest release](https://github.com/kdg1992/keyrook/releases/latest) before
reporting a problem.

## Reporting a vulnerability

Report vulnerabilities privately through GitHub Private Vulnerability
Reporting: open the repository's **Security** tab and choose **Report a
vulnerability**. Do not open a public issue, pull request or discussion for a
suspected vulnerability.

Please include:

- the affected Keyrook version, operating system and installer type (MSI, DMG,
  DEB, RPM or a source build);
- the affected component, for example vault format, cryptography, locking,
  clipboard handling, import/export, SSH key handling or the update check;
- steps to reproduce, with a minimal proof of concept if possible;
- the impact you expect and any conditions an attacker needs.

Never attach real vaults, key files, backups, exports, passwords, private keys
or hostnames. Reproduce the problem with a newly created vault and synthetic
test data; if a file is needed, create one only for the report.

## What to expect

Reports are handled on a best-effort basis by a single maintainer. The aim is
to acknowledge a report within a few days, keep you informed while it is
investigated, and publish a fix together with a GitHub security advisory.
Please allow time for a fixed release before disclosing details publicly.
Credit is given in the advisory unless you prefer otherwise.

## Security model

The protections Keyrook provides, and the limits it documents deliberately
(for example JVM memory erasure, clipboard history and operating-system event
coverage), are described in [docs/SECURITY.md](../docs/SECURITY.md). Behavior
that matches a documented limitation is not a vulnerability, but reports that
show a limit is worse than documented are welcome.
