# Security Policy

## Supported Versions

Security fixes are handled on the current active development line. Public releases should be built from tagged commits.

## Reporting a Vulnerability

Please report security issues privately through GitHub Security Advisories after the repository is public. If private advisories are not available yet, open a minimal issue asking for a secure contact channel without posting exploit details, tokens, cookies, private URLs, or proof-of-concept payloads.

Useful reports include:

- Affected version or commit.
- Device and Android version.
- Steps to reproduce.
- Expected and actual behavior.
- Logs with cookies, tokens, and private URLs removed.

## Sensitive Data

Do not publish:

- Signing keys or keystores.
- `local.properties`.
- Cookie profile exports.
- Download logs containing private URLs.
- Crash logs or debug captures with personal paths or website tokens.

## Release Builds

Release signing uses environment variables and GitHub Actions secrets. Never commit signing material to the repository.
