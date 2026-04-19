package com.znatok.hdmon.peripheral;

import com.znatok.hdmon.block.HDMonBlockEntity;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class HDMonPeripheral implements IPeripheral {
    private final HDMonBlockEntity be;
    private volatile boolean detached = false;
    private final Set<IComputerAccess> computers =
            Collections.synchronizedSet(new HashSet<>());

    public HDMonPeripheral(HDMonBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() { return "hdmonitor"; }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        if (other == this) return true;
        if (!(other instanceof HDMonPeripheral p)) return false;
        return p.be == this.be || (p.be != null && this.be != null
                && p.be.getBlockPos().equals(this.be.getBlockPos()));
    }

    @Override
    public Object getTarget() { return be; }

    public void markDetached() { this.detached = true; }

    @Override
    public void attach(IComputerAccess computer) {
        computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        computers.remove(computer);
    }

    /** Called on server thread from HDMonBlock.useWithoutItem. */
    public void fireTouch(int xPixel, int yPixel) {
        synchronized (computers) {
            for (IComputerAccess ca : computers) {
                try {
                    ca.queueEvent("monitor_touch", ca.getAttachmentName(), xPixel, yPixel);
                } catch (Exception ignored) {
                    // guard against disposed accesses
                }
            }
        }
    }

    private void checkAttached() throws LuaException {
        if (detached || be == null || be.isRemoved()) {
            throw new LuaException("hdmonitor detached");
        }
    }

    // ---- Lua API ----

    @LuaFunction
    public final Object[] getSize() throws LuaException {
        checkAttached();
        return new Object[]{ be.getCols() * HDMonBlockEntity.WIDTH, be.getRows() * HDMonBlockEntity.HEIGHT };
    }

    @LuaFunction
    public final Object[] getBlockSize() throws LuaException {
        checkAttached();
        return new Object[]{ be.getCols(), be.getRows() };
    }

    @LuaFunction
    public final void setPixel(int x, int y, int r, int g, int b) throws LuaException {
        checkAttached();
        be.setPixel(x, y, r, g, b);
    }

    @LuaFunction
    public final void clear(int r, int g, int b) throws LuaException {
        checkAttached();
        be.clearBuffer(r, g, b);
    }

    @LuaFunction
    public final void setAutoFlush(boolean v) throws LuaException {
        checkAttached();
        be.setAutoFlush(v);
    }

    @LuaFunction
    public final void flush() throws LuaException {
        checkAttached();
        be.flushNow();
    }

    @LuaFunction
    public final void drawRect(int x, int y, int w, int h, int r, int g, int b) throws LuaException {
        checkAttached();
        if (w <= 0 || h <= 0) throw new LuaException("w,h must be positive");
        be.drawRect(x, y, w, h, r, g, b);
    }

    /**
     * drawImage(x, y, w, h, bytes) — {@code bytes} is a Lua string of length w*h*3 RGB bytes.
     * Uses IArguments to receive the string as a ByteBuffer (no intermediate String copy).
     */
    @LuaFunction
    public final void drawImage(IArguments args) throws LuaException {
        checkAttached();
        int x = args.getInt(0);
        int y = args.getInt(1);
        int w = args.getInt(2);
        int h = args.getInt(3);
        if (w <= 0 || h <= 0) throw new LuaException("w,h must be positive");
        ByteBuffer bb = args.getBytes(4);
        if (bb == null) throw new LuaException("bytes is nil");
        int need = w * h * 3;
        int avail = bb.remaining();
        if (avail < need) {
            throw new LuaException("bytes too short: expected " + need + " got " + avail);
        }
        byte[] rgb = new byte[need];
        int pos = bb.position();
        bb.get(rgb, 0, need);
        bb.position(pos);
        be.drawImage(x, y, w, h, rgb);
    }

    @LuaFunction
    public final void drawText(int x, int y, String text, int r, int g, int b, Optional<Integer> scale)
            throws LuaException {
        checkAttached();
        if (text == null) throw new LuaException("text is nil");
        int s = scale.orElse(1);
        be.drawText(x, y, text, r, g, b, s);
    }
}
