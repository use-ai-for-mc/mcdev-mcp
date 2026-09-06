# `record_video` — DebugBridge Protocol Spec

> **Pinned Node oracle evidence:** Historical source paths below refer to the commit recorded in
> [`contracts/node-oracle.json`](../contracts/node-oracle.json), not files in the current Java worktree.

Adds a new request type to the DebugBridge WebSocket protocol so the mcdev-mcp tool `mc_record_video` can capture short bursts of the Minecraft framebuffer for debugging temporal rendering issues (animation glitches, shader bugs, particles, sub-tick visual artifacts that a single screenshot cannot resolve).

This document is the contract between the MCP server (this repo) and the [DebugBridge mod](https://github.com/use-ai-for-mc/debugbridge). Both sides implement against it.

It complements the existing `screenshot` handler — same threading model, same JPEG output, same file path semantics. Skim [src/tools/runtime/screenshot.ts](../src/tools/runtime/screenshot.ts) on the MCP side for the closest-analog tool surface, then read this doc for what's new.

---

## 1. Wire protocol

### Request

```jsonc
{
  "id": "req_42",
  "type": "record_video",
  "payload": {
    "frames":    60,              // required, integer, 1..MAX_FRAMES
    "interval":  "frame",         // "frame" | number(ms, >=1). Default "frame".
    "output":    "grid",          // "grid" | "frames". Default "grid".
    "gridCols":  null,            // optional integer, only used when output="grid".
                                  //   If null/omitted, mod picks (≈ ceil(sqrt(frames))).
    "downscale": 2,               // optional integer >=1. Default 2. Same semantics as screenshot.
    "quality":   0.75             // optional number in [0.05, 1.0]. Default 0.75.
  }
}
```

**Field semantics:**

- `frames` — number of frames to capture. Rejected if `> MAX_FRAMES` (see §3).
- `interval`:
  - `"frame"` — capture on every render-thread tick, in order. The natural cadence: at locked 60 fps you get 60 Hz; at unlocked / variable fps the cadence follows the game.
  - integer/float ms — capture the next frame whose render-time is `>= last_capture_time + interval`. Allows decimation (e.g. `interval: 100` = ~10 fps).
- `output`:
  - `"grid"` — compose all frames into a single JPEG laid out as a `gridRows × gridCols` grid (row-major, frame 0 in the top-left). One file, one path.
  - `"frames"` — write N separate JPEGs, return N paths in capture order.
- `downscale` — applied per frame, before grid composition. Same semantics as the existing `screenshot` handler.
- `quality`:
  - In `frames` mode, applied per output JPEG.
  - In `grid` mode, downscaled frames are pasted into a single image and `quality` applies **once** to the final composed JPEG. Frames are *not* JPEG-encoded individually on the way into the grid (no double-JPEG round-trip).

### Response — success, `output: "grid"`

```jsonc
{
  "id": "req_42",
  "success": true,
  "result": {
    "mode":       "grid",
    "path":       "<gameDir>/debugbridge-recordings/<reqId>/recording.jpg",
    "width":      1920,           // composed image width
    "height":     1080,           // composed image height
    "sizeBytes":  483210,
    "mimeType":   "image/jpeg",
    "frameCount": 60,
    "frameWidth": 320,            // per-frame width inside the grid
    "frameHeight":180,
    "gridCols":   8,
    "gridRows":   8,              // ceil(frameCount / gridCols); last row may be partial
    "captureMs":  1023,           // wall-clock duration of the capture phase
    "intervalMs": 16.7,           // observed mean interval between captured frames
    "dropped":    0               // frames dropped because the encoder fell behind. Always present; 0 for interval:<ms> mode.
  }
}
```

### Response — success, `output: "frames"`

```jsonc
{
  "id": "req_42",
  "success": true,
  "result": {
    "mode":       "frames",
    "paths":      [
      "<gameDir>/debugbridge-recordings/<reqId>/frame-0000.jpg",
      "<gameDir>/debugbridge-recordings/<reqId>/frame-0001.jpg",
      "..."
    ],
    "frameWidth": 320,
    "frameHeight":180,
    "mimeType":   "image/jpeg",
    "frameCount": 60,
    "captureMs":  1023,
    "intervalMs": 16.7,
    "sizeBytes":  4823100,        // sum of file sizes; the MCP tool prints this as KB
    "dropped":    0               // frames dropped because the encoder fell behind. Always present; 0 for interval:<ms> mode.
  }
}
```

### Response — error

Standard `BridgeResponse` shape with `success: false` and `error` set to one of the codes in §4.

---

## 2. Behavioral contract

### Threading

Mirror the `screenshot` handler:

- **Frame capture runs on the render thread.** Each capture stalls the thread for at most one frame (GPU readback of the downscaled framebuffer).
- **JPEG encoding and disk I/O run off the render thread.** Capture produces a `NativeImage` (or equivalent raw buffer); encode + write happens on a worker. This is the only way to keep the render thread free at `interval: "frame"` cadence.
- **Grid composition** (in `grid` mode) runs on the worker thread, after the last frame is captured and encoded. The response is sent only after the grid file is on disk.

Concretely: the request returns when *all* output files exist and are flushed.

### Frame timing

- `interval: "frame"` → capture on every render tick. If encoding can't keep up, drop the in-flight capture rather than stalling the render thread (and increment a `dropped` counter — log it, optionally surface in the response as a future extension). Do not buffer raw frames unboundedly.
- `interval: <ms>` → on each render tick, check `now - last_capture_time >= interval`; if yes, capture. This is the cheap path and should never drop frames in practice (encoder has time between captures).

### State edge cases

- **Game paused.** Captures still proceed; each frame is the last rendered frame. The recording is effectively N copies of the same image. Document this; do not reject the request.
- **Window minimized / hidden.** If the framebuffer is unavailable, return `FRAMEBUFFER_UNAVAILABLE` immediately rather than block.
- **Window resize mid-recording.** All frames in one recording must share dimensions. Use the dimensions captured at frame 0; if the framebuffer changes size, either rescale to frame 0's dimensions or abort with `FRAMEBUFFER_RESIZED` (implementer's choice — pick whichever is simpler in your render hooks, but document it).
- **Concurrent record requests.** Refuse the second with `BUSY`. Do not queue.
- **Disk write failure mid-recording.** Abort the recording, clean up partial files, return `IO_ERROR`. Do not return a partial result.

### Validation & caps (§3)

Validate the payload up front; reject with `INVALID_INPUT` before starting capture if any field is out of range.

---

## 3. Caps & defaults

| Knob | Default | Cap | Rationale |
|---|---|---|---|
| `frames` | (required) | `MAX_FRAMES = 300` | 5 s at 60 Hz. Composed grid at downscale=2 quality=0.75 stays under ~3 MB. |
| `interval` | `"frame"` | `>= 1 ms` if numeric | Sub-ms intervals are nonsense at the render-tick granularity. |
| `output` | `"grid"` | n/a | Grid is the Claude-friendly default; frames is for cases that need per-frame `Read`. |
| `gridCols` | `ceil(sqrt(frames))` | `>= 1`, `<= frames` | Square-ish layouts compose cleanest. |
| `downscale` | `2` | `>= 1` | Same as `screenshot`. |
| `quality` | `0.75` | `[0.05, 1.0]` | Same as `screenshot`. |

Reject `frames > MAX_FRAMES` rather than silently clamping. The MCP tool description quotes this number; clamping would make the contract a lie.

---

## 4. Error codes

All errors return `success: false` with `error` set to a string whose first token is one of these codes (e.g. `"INVALID_INPUT: frames=500 exceeds MAX_FRAMES=300"`). The MCP tool surfaces the full string.

| Code | Meaning |
|---|---|
| `INVALID_INPUT` | Payload validation failed (missing field, out-of-range value, unknown `output` mode, etc.). |
| `BUSY` | Another `record_video` is in progress. |
| `FRAMEBUFFER_UNAVAILABLE` | Window minimized, GL context lost, or framebuffer otherwise inaccessible at capture start. |
| `FRAMEBUFFER_RESIZED` | (Only if implementer chose abort-on-resize.) Window size changed mid-recording. |
| `IO_ERROR` | Disk write failed; partial files cleaned up. |
| `INTERNAL` | Unexpected exception; include the message. |

---

## 5. File output & cleanup

- **Layout — one subdir per recording** under `<gameDir>/debugbridge-recordings/`, created lazily on first call:

  ```
  <gameDir>/debugbridge-recordings/<requestId>/recording.jpg          # grid mode
  <gameDir>/debugbridge-recordings/<requestId>/frame-NNNN.jpg         # frames mode (NNNN zero-padded to 4 digits)
  ```

  Rationale: cleanup (delete one recording = `rm -rf <requestId>/`, no filename parsing); no 300-sibling-files clutter in `frames` mode; future retention policy ("delete subdirs older than N hours") only needs to stat directory mtimes.

  The screenshot handler keeps its existing OS-temp behavior — different lifecycle, not changed here.

- Return **absolute paths** so the MCP server (same machine) can hand them straight to the user's `Read` tool.

- **Cleanup policy: leak for now.** Files accumulate until the user wipes `<gameDir>/debugbridge-recordings/`. Acceptable for v1 because the location is single, known, and easy to clear. Retention/auto-cleanup is tracked as a follow-up — the subdir-per-recording layout exists precisely so that future cleanup is trivial to add.

---

## 6. Examples

### Example 1 — 1-second capture at 60 Hz, contact-sheet output

Request:
```json
{ "id": "req_1", "type": "record_video",
  "payload": { "frames": 60, "interval": "frame", "output": "grid" } }
```

Result: one JPEG at `<gameDir>/debugbridge-recordings/req_1/recording.jpg`, an 8×8 grid (last row partial — 60 frames into 8 cols = 7 full rows + 4 in the 8th) of 960×540 thumbnails (assuming a 1920×1080 window at downscale=2).

### Example 2 — 30 frames at 100 ms apart, separate files

Request:
```json
{ "id": "req_2", "type": "record_video",
  "payload": { "frames": 30, "interval": 100, "output": "frames", "downscale": 4 } }
```

Result: 30 JPEGs under `<gameDir>/debugbridge-recordings/req_2/` (`frame-0000.jpg` … `frame-0029.jpg`), each 480×270, ~3 s total wall-clock duration.

### Example 3 — over-cap, rejected

Request:
```json
{ "id": "req_3", "type": "record_video",
  "payload": { "frames": 1000, "interval": "frame", "output": "grid" } }
```

Response:
```json
{ "id": "req_3", "success": false,
  "error": "INVALID_INPUT: frames=1000 exceeds MAX_FRAMES=300" }
```

---

## 7. Out of scope for v1

These are deliberately *not* in this spec — file follow-up issues if needed:

- **Pre-trigger / ring buffer.** Continuously keep the last K frames in memory; dump on demand. Powerful for unpredictable events, but requires persistent allocation in the mod even when not recording. Defer until a concrete debugging scenario asks for it.
- **MP4/GIF output.** No agent-facing benefit (Claude can't view video). User can record manually with OS-level tooling if they want a video.
- **Capture from arbitrary render targets** (e.g. a specific entity's view, an off-screen camera). Single primary framebuffer only.
- **Audio.** No.
- **Capture-while-loading / capture-before-render-init.** Out of scope; let `FRAMEBUFFER_UNAVAILABLE` cover it.

---

## 8. MCP-side surface (for context)

The MCP server will add `mc_record_video` to `src/tools/runtime/`, wiring the request through `bridgeSession.send("record_video", payload)` and returning the path(s) to the agent. The tool description will quote the caps in §3 so the model picks sensible parameters.

The MCP tool can ship before the mod-side handler — until the mod recognizes `record_video`, calls will surface as a clean "unknown request type" error.
