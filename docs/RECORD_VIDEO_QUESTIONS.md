# `record_video` — open questions from the DebugBridge (mod) side

> **Pinned Node oracle evidence:** Historical source paths below refer to the commit recorded in
> [`contracts/node-oracle.json`](../contracts/node-oracle.json), not files in the current Java worktree.

From the implementer side of [RECORD_VIDEO_PROTOCOL.md](./RECORD_VIDEO_PROTOCOL.md). Three small ambiguities surfaced while drafting the mod-side implementation plan; pinning them now keeps both sides honest and avoids a v1.1 round-trip.

Each question has a proposed answer from the mod side — please confirm, push back, or pick a different option.

---

## 1. Grid mode: encode JPEG once, not per-frame

Spec §1 says:

> `downscale` and `quality` — identical semantics to the existing `screenshot` handler. Applied per frame *before* grid composition (in `grid` mode).

Read literally, that suggests: downscale → JPEG-encode each frame → compose grid → JPEG-encode again. Two JPEG round-trips per frame.

**Proposal:** in `grid` mode, downscale per frame, buffer the raw pixels, paste into a single big `BufferedImage`, JPEG-encode once at the end. `quality` applies to the *single* output JPEG. (`frames` mode is unchanged — one JPEG per file, `quality` applies per-file.)

This matches the spirit of §2's "Grid composition runs on the worker thread, after the last frame is captured and encoded" — we just don't encode-then-decode per frame on the way to the composer.

Confirm wording update, or push back if there was a reason to JPEG twice?

---

## 2. Surface `dropped` frames in v1?

§2 says:

> If encoding can't keep up, drop the in-flight capture rather than stalling the render thread (and increment a `dropped` counter — log it, optionally surface in the response as a future extension).

For `interval: "frame"` on a high-fps client, drops will be common. The MCP-side agent generally won't know whether a 60-frame capture actually has 60 distinct frames or 45 distinct + 15 dropped without this number.

**Proposal:** add `"dropped": <int>` to both success-response shapes in v1 (always present, 0 in numeric-interval mode). Free to compute, useful diagnostic, MCP tool can choose to surface it or not.

OK to add, or hold for v1.1?

---

## 3. Output directory: `<gameDir>/debugbridge-recordings/`, not OS temp

Spec §5 says:

> Write outputs under the same temp directory the `screenshot` handler uses (in the mod's working dir / game dir). Reuse its naming convention; suggest `recording-<requestId>.jpg` for grids and `recording-<requestId>-frame-NNNN.jpg` for separate frames.

The existing screenshot handler actually uses `Files.createTempFile("debugbridge-screenshot-", ".jpg")`, which writes to the **OS temp directory** (`/tmp` or equivalent) with a random suffix — not the game dir, and not with a predictable name.

This wasn't a problem for screenshots (one file, opaque name is fine), but for recordings we need:
- A predictable parent dir so 60 sibling frames land together.
- Names containing `<requestId>` per spec.

**Proposal:** write under `<gameDir>/debugbridge-recordings/`, created lazily. Names exactly as spec suggests (`recording-<reqId>.jpg`, `recording-<reqId>-frame-NNNN.jpg`). Screenshot handler stays on OS temp dir — not changed in this PR. Spec §5 wording updated to reflect "game dir, not OS temp."

Cleanup policy: same "leak forever for now" as screenshots; tracked as a follow-up. (Acceptable because the recordings dir is easy for the user to wipe — single known location, no random-suffixed files mixed into OS temp.)

Confirm, or prefer a different layout (e.g. one subdir per recording: `<gameDir>/debugbridge-recordings/<reqId>/frame-NNNN.jpg`)?

---

## Not blocking — just heads-up

- **Third Minecraft version exists.** CLAUDE.md on the mod side lists 1.19 and 1.21.11, but there's also a `fabric-26.2-dev` module live in the repo. Three provider implementations, three mixin files. Mentioning so the MCP-side tool description doesn't say "1.19 and 1.21.x only" if it currently does.
- **`BUSY` extends to screenshot.** While a `record_video` is in progress, single-shot `screenshot` requests will also return `BUSY` (shared render thread, otherwise interleaved captures muddy the recording timing). Will document in the mod's screenshot handler. No change needed MCP-side.
