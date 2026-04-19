-- HDMonitor addon: adds Cyrillic support to CC terminal.
-- Wraps term.write/term.blit so that UTF-8 Cyrillic in Lua strings is converted
-- to Windows-1251 bytes before rendering. The term font atlas (overridden by hdmon
-- mod) has Cyrillic glyphs at CP1251 positions.

if _HDMON_CYR_LOADED then return end
_HDMON_CYR_LOADED = true

-- UTF-8 codepoint -> CP1251 byte
local u2c = {
    [0x0401] = 0xA8, [0x0451] = 0xB8, -- Yo yo
}
for i = 0, 31 do
    u2c[0x0410 + i] = 0xC0 + i -- A..Ya (upper)
    u2c[0x0430 + i] = 0xE0 + i -- a..ya (lower)
end

-- Decode one UTF-8 codepoint starting at byte index i.
-- Returns (codepoint, byteLength) or (nil, 1) if malformed.
local function utf8decode(s, i)
    local b = s:byte(i)
    if not b then return nil, 1 end
    if b < 0x80 then return b, 1 end
    if b >= 0xC0 and b < 0xE0 and i + 1 <= #s then
        local b2 = s:byte(i + 1)
        return ((b - 0xC0) * 0x40) + (b2 - 0x80), 2
    end
    if b >= 0xE0 and b < 0xF0 and i + 2 <= #s then
        local b2, b3 = s:byte(i + 1), s:byte(i + 2)
        return ((b - 0xE0) * 0x1000) + ((b2 - 0x80) * 0x40) + (b3 - 0x80), 3
    end
    return nil, 1
end

-- Convert UTF-8 string -> CP1251 byte string.
local function utf8toCp(s)
    if type(s) ~= "string" then return s end
    local hasHi = false
    for i = 1, #s do
        if s:byte(i) >= 0x80 then hasHi = true; break end
    end
    if not hasHi then return s end -- ASCII fast path

    local out = {}
    local i = 1
    while i <= #s do
        local cp, n = utf8decode(s, i)
        if cp then
            if cp < 0x80 then
                out[#out + 1] = string.char(cp)
            else
                local m = u2c[cp]
                if m then
                    out[#out + 1] = string.char(m)
                else
                    out[#out + 1] = "?"
                end
            end
        else
            out[#out + 1] = "?"
        end
        i = i + n
    end
    return table.concat(out)
end

-- Convert UTF-8 text + fg/bg -> CP1251 aligned byte-per-char.
-- Because UTF-8 uses multiple bytes per codepoint but our output is 1 byte per
-- char, we must re-align fg/bg to the output. We index fg/bg by codepoint index.
local function utf8toCpBlit(text, fg, bg)
    if type(text) ~= "string" then return text, fg, bg end
    if type(fg) ~= "string" or type(bg) ~= "string" then return utf8toCp(text), fg, bg end
    local hasHi = false
    for i = 1, #text do
        if text:byte(i) >= 0x80 then hasHi = true; break end
    end
    if not hasHi then return text, fg, bg end

    local tOut, fOut, gOut = {}, {}, {}
    local i = 1
    local cpIdx = 1
    while i <= #text do
        local cp, n = utf8decode(text, i)
        local tCh = "?"
        if cp then
            if cp < 0x80 then
                tCh = string.char(cp)
            else
                local m = u2c[cp]
                if m then tCh = string.char(m) end
            end
        end
        -- fg/bg are sized in original-string bytes; we index by codepoint index
        -- (closest mapping: 1 color per input codepoint).
        local fCh = fg:sub(cpIdx, cpIdx)
        local gCh = bg:sub(cpIdx, cpIdx)
        tOut[#tOut + 1] = tCh
        fOut[#fOut + 1] = (fCh == "") and "0" or fCh
        gOut[#gOut + 1] = (gCh == "") and "f" or gCh
        i = i + n
        cpIdx = cpIdx + 1
    end
    return table.concat(tOut), table.concat(fOut), table.concat(gOut)
end

-- Patch a terminal-like object in place.
local function patchTerm(t)
    if not t or t._HDMON_CYR_PATCHED then return t end
    local origWrite = t.write
    if type(origWrite) == "function" then
        t.write = function(text) return origWrite(utf8toCp(text)) end
    end
    local origBlit = t.blit
    if type(origBlit) == "function" then
        t.blit = function(text, fg, bg)
            local t2, f2, g2 = utf8toCpBlit(text, fg, bg)
            return origBlit(t2, f2, g2)
        end
    end
    t._HDMON_CYR_PATCHED = true
    return t
end

-- Patch the global term table (used by print, read, shell, edit, etc.)
patchTerm(term)

-- Wrap window.create so windows created after load also get Cyrillic support.
if window and window.create then
    local origWindowCreate = window.create
    window.create = function(parent, x, y, w, h, visible)
        local win = origWindowCreate(parent, x, y, w, h, visible)
        return patchTerm(win)
    end
end

-- Wrap io.write -> go through term.write (our patched one).
if io and io.write then
    local origIoWrite = io.write
    io.write = function(...)
        local args = { ... }
        for k = 1, select("#", ...) do
            if type(args[k]) == "string" then
                args[k] = utf8toCp(args[k])
            end
        end
        return origIoWrite(table.unpack(args))
    end
end
