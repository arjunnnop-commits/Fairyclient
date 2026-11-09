# Fairyclient

MediaTek BROM / Download-Agent based force-recovery tool for hard-bricked
devices (BROM-only detection, no fastboot/ADB). Runs entirely from an
Android phone over USB-OTG — no PC required.

## What's implemented

- USB host detection of a MediaTek device in BROM mode (`VID 0x0E8D`)
- BROM handshake (`BromProtocol.handshake()`)
- Reading the chip hardware code
- Sending a Download Agent (DA) and jumping to it
- DA-level partition table read, and `boot` / `vendor_boot` write
- A live console screen, same pattern as Fairy Flasher X's ROM screen

## What you must still provide / wire up

- **DA files.** This app does not ship or generate Download Agent
  binaries — they're chip-specific. Use the same DA files SP Flash Tool /
  mtkclient use for the target chipset.
  File picking is wired up with a **temporary, simple approach**:
  `ActivityResultContracts.OpenDocument()` opens the system file picker,
  and the picked file is copied into the app's `cacheDir` so the rest of
  the code (which works with plain `java.io.File`) can read it. This is
  fine for testing and normal use, but it re-copies the file on every
  pick (slow for very large images) and doesn't persist access across
  app restarts. If that becomes a problem, switch to keeping the
  `content://` Uri and reading via `ContentResolver` streams directly
  instead of copying.
- **DA is session-only.** The picked DA is copied into the app's private
  `cacheDir` only for the duration of one flash, and is deleted right
  after the flash finishes (success or failure), and also on app close
  if no flash was run. It's never kept around between sessions — pick it
  fresh each time you need to use it.
- **Secure Boot / SLA-DAA.** On chips where the OEM enabled Download
  Agent Authentication, BROM will reject any DA that isn't signed/
  authorized for that exact device. This app surfaces that as a clear
  error (`DA rejected: ...`) instead of crashing, but it does **not**
  attempt to bypass that protection — there's no generic bypass; it's
  exploit- and chip-specific, and must come from a DA the user already
  has permission/a bypass for.
- **DA load address.** `FlashController` uses `0x40000000` as a default
  SRAM staging address. Some DA files need a different address — check
  the DA's own documentation/header for the chipset you're targeting and
  make this configurable per DA if needed.
- **Protocol byte-exactness.** The opcodes in `BromProtocol` and
  `DaSession` follow the publicly documented handshake/command shape
  used by community tools, but MediaTek has shipped slightly different
  DA command sets across chip generations (legacy DA vs DA v6 / xflash).
  Treat this as a solid skeleton to test and adjust against real
  hardware, not a drop-in guarantee — I have no physical device to test
  against here, so the first run will likely need some debugging against
  your actual target chips.

## Build

Pushes to `main` (or manually via "Run workflow") build a debug APK via
GitHub Actions — see `.github/workflows/build.yml`. The resulting APK is
attached as a workflow artifact.

## Safety

- Always confirm the DA and boot/vendor_boot images match the exact
  device codename before flashing — wrong images can make a hard brick
  permanent (unrecoverable even via BROM).
- BROM mode only stays responsive for a couple of seconds after power-on;
  the handshake should run immediately once USB permission is granted.
