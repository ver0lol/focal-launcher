# Instructions for agents

## Start here

This is focal-launcher, the source of the owner's P30 projector UI, not a firmware distribution. Read `docs/CURRENT-STATE.md`, `docs/ARCHITECTURE.md` and `docs/DEVELOPMENT.md` before changing behavior. Primary components are `launcher/` and `singbox-integration/`. Boot research tools belong to the separate projector-root repository and are intentionally not included here.

The owner's current goal is a usable minimalist launcher, YouTube, VPN and web remote. Implement requested features through ordinary Android APIs and normal APK updates wherever possible. Local edits, builds and offline checks do not require extra confirmation. A user request for a normal app/UI change authorizes its necessary reversible implementation and verification. Do not introduce repeated permission prompts for already-authorized work.

## Device safety boundary — preserve exactly

Target family: P30_H713_V1.1_CHIHI_emcp, Android 11 / API 30, sun50iw12p1 / ares, armeabi-v7a, original slot `_a`. The exact identity and ADB address are pinned in ignored `local/device.json`. Device tools must reject missing/template configuration and identity mismatches before staging or installing anything. Never learn an expected identity automatically from whichever ADB device happens to be connected.

DO NOT write raw block devices, flash, erase, unlock, change AVB/boot-control state, switch slots, reboot, enter fastboot/FEL/recovery, install root, or start/restart a root daemon until BOTH an independent safe recovery route has been demonstrated and the owner explicitly approves the exact action. A generic request to continue does not authorize these operations. Independent recovery remains unverified. Slot B contains zero-filled images; A/B names do not prove recovery.

Never read the Allwinner `sysfs usb_device` / `usb_host` trigger nodes or change `persist.sys.usb0device`: reads of those special nodes can change USB roles. Do not guess hardware PWM limits or change fan controls while implementing brightness UI.

Keep original images immutable. Block reads use the parent eMMC node, pinned geometry, bounds, GPT CRC and exact lengths/hashes. `adb exec-out` merges remote stderr; redirect `dd` stderr away from binary data. No automated preparation script may contain a flash/write command.

Existing factory `qw --daemon` root has been verified without flashing. The separate projector-root repository supplies the root diagnostics script; this repository only bootstraps the app input helper through that ALREADY RUNNING daemon. It verifies identity, hashes, socket and staging permissions. Never add a daemon-start, remount, property-change or weakened-check fallback. Root availability is not permission to reboot/flash/alter USB.

## Build and deployment

- `make check`: source/privacy/config validation plus offline client contracts; no ADB or network.
- `./focal check`: offline only, never installs dependencies or opens a device. `./focal build`: prepare Arch dependencies and host-pinned SDK, check and build only.
- `./focal update` / `make update`: explicit authorized build + deployment; original private kit required. Do not invoke for publishing or tooling-only tasks.
- `make build`: focal-launcher APK in `dist/`; no device access.
- `make vpn`: optional overlay with `local/vpn.json`; preserves the pinned original core/native libraries.
- `make install`: explicit live deployment; checks the pinned device and APK digest, then normal `adb install -r`. Use only within the user's authorized device task. It does not reboot or change default HOME.
- Keep `--test-key` strictly build-only; never add a test-signature install bypass. Preserve certificate verification before ADB/staging. The separate PRIVATE update kit extracts into ignored local/ and must never be added to public archives or Git.
- Preserve the original signing key. focal-launcher and its VPN companion need the same certificate. Never uninstall an app or clear its data merely to bypass a signature mismatch or downgrade error.
- Increase both manifest `versionCode` and `versionName` for a deployed app change. Update `docs/CURRENT-STATE.md` and relevant instructions. Documentation/tooling-only changes do not require an APK version bump or device installation.
- Existing helper constants/hashes are firmware constraints, not portable defaults to remove for convenience. Build paths are configurable; device guard expectations remain strict.

## Web input boundaries

HTTP stays on the local network under normal app UID. Preserve pairing, hashed tokens, Host/Origin checks, request/queue limits and CSP. Input worker stays UID/GID 2000 with CapEff=0, connects once over a private verified Unix channel and exits with the app. No persistent root request worker, IP input listener, generic shell, arbitrary Activity launch or uploaded executable endpoint.

Buttons are allowlisted in `RemoteKeyCodes.java` on both sides. Text is bounded and validated before any event, encoded as data and never interpolated into a shell command. Keep text and navigation ordered; do not retry ambiguous text submissions automatically. Never log typed text, PINs, bearer tokens or VPN configuration.

## Working conventions

Use a feature branch when appropriate. Avoid broad reformatting of the compact existing Java/JS. Preserve favorites, theme, pairing, VPN routing and the stock projector settings/launcher. Do not resign TizenTube: its name is an alias in focal-launcher, preserving account data and upstream updates.

Keep `local/`, `work/`, `dist/`, `project.json`, `backups/`, `artifacts/`, `evidence/`, private config, APKs and keystores out of Git. Do not use `git add -f` to bypass ignores. Runtime evidence can be written to the ignored directories; only a reviewed, sanitized summary belongs in docs.

Run checks appropriate to the change. Do not operate the projector from offline checks or CI. A focused physical UI check can complement offline checks when deployment is authorized. The owner actively uses the web remote: unexpected navigation can be their input; do not repeatedly fight it or diagnose it as a bug without evidence.

Record actual observations separately from hypotheses. Report what changed, what was tested and what remains unverified. This owner-handoff has no remote. Its recipient may explicitly request publication to their own GitHub; read OWNER_SETUP.md and docs/PUBLISHING.md. A publishing task does not authorize device access. Establish the recipient account, display name, verified email/noreply address and visibility; never assume the sender account. Use scripts/set_owner.py only before remote setup on the single unpublished bootstrap commit. Do not force-push or rewrite published history.

## Naming and compatibility

The project, repository directory and Android display name are `focal-launcher`. `_VeR0` is the owner's requested header label, not the product name. Keep `dev.projector.rice`, the `rice` SharedPreferences file, signing-key alias/file, signature permission and VPN RPC identifiers unchanged: they preserve updates and existing data. The separate `projector-root` repository contains the firmware/root research. This archive contains only the launcher, its VPN companion and the minimal installer/configuration tooling.

README screenshots belong in `docs/images/` and must show the actual reviewed home screen, without pairing PIN, account identity or private URLs. Never run broad `git clean -fdx` / `git clean -X` on this working copy: ignored backup/config directories contain real owner data.
