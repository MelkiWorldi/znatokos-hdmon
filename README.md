# HDMonitor (znatokos-hdmon)

A NeoForge 1.21.1 addon for CC:Tweaked adding a high-resolution RGB monitor
block. Every block is 160x90 true-color pixels (16:9); up to 12x12 blocks
auto-merge into a single logical screen of 1920x1080. Framebuffer updates are
shipped as Deflater-compressed 10x10 tile diffs and right-clicks are reported
to Lua as `monitor_touch` events — same shape as vanilla CC monitors.

### Resolution table

| Group     | Pixels      | Notes          |
|-----------|-------------|----------------|
| 1x1       | 160x90      | 16:9 native    |
| 4x4       | 640x360     | 360p           |
| 8x8       | 1280x720    | 720p           |
| 12x12     | 1920x1080   | 1080p (max)    |

## Installation

1. Install NeoForge 21.1.226 for Minecraft 1.21.1.
2. Install CC:Tweaked 1.117.1 (or newer 1.21.1 build).
3. Drop `hdmon-0.1.0.jar` in `mods/`.

## Crafting

```
G G G
G E G      G = glass pane, E = eye of ender, R = redstone
G R G
```

Yields one `hdmon:hd_monitor`.

## Peripheral API

Peripheral type: `hdmonitor`. Any block in the group exposes the full logical
screen — `cols*160` x `rows*90` pixels, origin at top-left.

```lua
local m = peripheral.find("hdmonitor")

-- Info
local w, h   = m.getSize()       -- e.g. 320, 90 for a 2x1 group
local bw, bh = m.getBlockSize()  -- e.g. 2, 1

-- Drawing
m.clear(20, 20, 40)                              -- fill RGB
m.setPixel(5, 5, 255, 255, 255)                  -- single pixel
m.drawRect(10, 10, 50, 20, 255, 100, 0)          -- filled rect
m.drawText(10, 40, "HD Monitor", 255,255,255, 2) -- bitmap text, scale=2
m.drawText(10, 60, "Привет, мир!", 200, 200, 255, 1)

-- drawImage: raw RGB byte-string, length w*h*3
local red4x4 = string.rep(string.char(255, 0, 0), 16)
m.drawImage(80, 10, 4, 4, red4x4)

-- Buffering
m.setAutoFlush(false)   -- default true; when false you must flush() manually
m.flush()               -- push pending updates to clients immediately
```

### drawText

- 8x12 bitmap font derived from DejaVu Sans Mono.
- Supports ASCII `0x20..0x7E` and Cyrillic block `U+0400..U+04FF`.
- Integer `scale` (optional, default 1, clamped to [1, 8]).
- `\n` advances to the next line; unknown codepoints render as `?`.
- UTF-8 input works directly — Lua files saved as UTF-8 (ZnatokOS editor
  default) pass `"Привет"` through transparently.

## Touch events

Right-clicking on the front face of any block in a group (with or without an
item) fires:

```lua
os.pullEvent("monitor_touch")
-- event, side, x, y
```

where `(x, y)` are pixel coordinates inside the whole group's framebuffer
(0..w-1, 0..h-1). Same event shape as CC:Tweaked's vanilla monitor.

```lua
local m = peripheral.find("hdmonitor")
m.clear(0, 0, 30)
m.drawRect(0, 0, 64, 32, 200, 60, 60)
while true do
  local ev, side, x, y = os.pullEvent("monitor_touch")
  if x < 64 and y < 32 then
    m.drawRect(0, 0, 64, 32, math.random(0,255), math.random(0,255), math.random(0,255))
  end
end
```

## Multi-block groups

Place multiple `hdmon:hd_monitor` blocks adjacent on the same wall (same
FACING, in the plane perpendicular to facing) and they auto-merge into one
logical screen.

- Max group size: 12x12 blocks (1920x1080 px).
- Only rectangular groups — non-rect layouts leave odd blocks as 1x1 screens.
- Groups are not persisted; they rebuild on chunk load (brief flicker is
  normal).
- Breaking a block dissolves the group; remaining blocks re-merge into any
  still-valid rectangle.

## Networking

- First sync (new client, chunk load, group reshape): full framebuffer via
  block entity update tag + a `FullBufferSyncPacket`.
- Steady-state updates: `TileDiffPacket` with only the 10x10 tiles that
  changed since the last sync, each tile Deflate-compressed (BEST_SPEED).
  Throttled to at most once per 100 ms per origin.

This keeps bandwidth proportional to on-screen change, not screen area —
scrolling one line of text on a 1920x1080 screen sends a few KB, not ~6 MB.

## Plan

See `.claude/plans/hdmon-mod.md` for the multi-day plan.
