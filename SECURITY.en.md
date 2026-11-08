# Reporting security issues

[中文](SECURITY.md)

Keep exploit details, credentials, private keys, real user data and complete diagnostic archives out of public issues, comments and pull requests.

## Report privately

Open this repository's [Security Advisories](https://github.com/Rakanrkk/dsh-Su/security/advisories) page:

- Prefer **Report a vulnerability** for a private submission — this repository has GitHub private vulnerability reporting enabled, and reports stay private.
- If it is unavailable, open an issue asking only how to contact the maintainer privately, then wait for a private contact route. Do not describe the vulnerability, attach reproduction data or identify an affected deployment in that public issue.

A `SECURITY.md` file enables nothing by itself; privacy comes from the repository's reporting settings. Confirm you are on the private reporting form, not a public issue, before submitting details.

## What to include

- App version, build number, and relevant engine or plugin versions. Include a commit for source-level reports.
- Android version, ABI, and whether the connection is local, SSH or DSH Remote.
- Impact, preconditions and minimal reproduction steps.
- A reproduction using fictional content, test accounts or synthetic fixtures, plus only the necessary redacted logs.
- What you have and have not verified, including results from other versions if available.

Real API keys, SSH private keys, login tokens and links granting access to private conversations are not required. If your own credentials have been exposed, revoke or replace them with the relevant service first.

## Scope and testing boundaries

Reports may cover the app, Dart communication packages, bundled bridge, runtime patches and companion services in this repository. Examples include credential exposure, unauthorized file access, approval bypasses, unauthorized phone actions and sharing access-control problems. For third-party dependency issues, identify the component and pinned version.

Reproduce issues only with your own devices, accounts and data, or in environments where you have authorization. Do not access other people's content, perform destructive actions or disrupt live services to validate a report. This policy does not authorize testing third-party services.

The local engine can execute tools and load plugins. Running the engine on the phone does not mean model requests stay on the device. See the [user guide](docs/guide/README.md) for permissions, model data and usage statistics.

In Root builds the bundled engine runs in a Linux environment started through Root, can execute tool commands the model requests (such as bash, git, python and apt), and can operate the phone: screenshots and on-screen text are sent to the connected model as phone-tool results; protected-page detection has limits — dark screens may be conservatively refused or missed and still captured, so do not treat the background screen as a privacy boundary. Turning phone control on requires your confirmation, the floating capsule offers pause and stop, and locking the screen stops the task; whether approvals are off, background confirmation, the app allowlist and lifting restrictions are covered in [phone control](docs/guide/en/04-phone-control.md). Model requests are handled by the model service you configure in the engine; the Root environment is not a security sandbox, so grant Root only on devices where you understand the consequences and do not treat background execution as isolation.

## Handling and disclosure

Use the private channel to discuss reproduction, impact and fix validation, then coordinate public disclosure with the maintainer. Identify affected versions in the report. This policy makes no fixed response-time, repair-deadline, bounty or long-term version-support commitment.
