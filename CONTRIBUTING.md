# Contributing to TagMod

## Code style

- **C** (`src/`): Linux kernel style (8-space tabs, 80-col, `{}` on same line for functions).
  GCC `-Wall -Wextra -Werror -static`.
- **Java** (`app/`): API 19, no Gradle, no external dependencies (org.json only).
  `javac -Xlint:all -Werror --release 8`. Inner classes go in separate files only when over
  ~200 lines.
- **Shell** (`*.sh`): `set -eu`, no bashisms. Scripts are run on-device with busybox ash.
- **Python** (`server/`): PEP 8, `unittest`. Third-party deps only in `requirements.txt`.

## Before submitting

- `build.sh` and `build-apk.sh` must exit clean on their targets.
- `cd server && python -m unittest test_tagmod_server` must pass.
- Don't add comments that explain the obvious; add them for architectural decisions,
  device quirks, or non-obvious algorithms.

## What not to commit

- `signing/` — APK signing keys (auto-generated, never shared)
- `backups/` — stock partition dumps (device-specific, large)
- `out/` — build outputs
- `log/` — flash logs
- `server/config.toml` — real server configuration (use `config.example.toml`)
- Credentials of any kind (wifi passwords, API keys, tokens)