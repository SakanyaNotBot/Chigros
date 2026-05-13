package com.wuying.phigros.game;

/**
 * Cursor for O(1) amortized event lookups during sequential-time playback.
 *
 * <p>Since time advances monotonically during normal playback, the cursor tracks the last-found
 * event index so subsequent lookups advance (or retreat on seek) a few positions instead of
 * scanning from 0.
 */
public final class EventCursor {
    public int index = -1;

    public void reset() {
        index = -1;
    }
}
