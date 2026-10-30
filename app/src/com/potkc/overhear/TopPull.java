package com.potkc.overhear;

/**
 * A pull down from the screen's top edge, which in full screen stands in for
 * the hidden status bar's (TopStrip opens the notification panel): a touch
 * that goes down within edge of the top and then moves at least distance
 * down, more down than sideways. Fires once per touch. Pixels; pure Java
 * (host tests).
 */
final class TopPull {
    private final float edge;
    private final float distance;
    private boolean armed;
    private float startX;
    private float startY;

    TopPull(float edge, float distance) {
        this.edge = edge;
        this.distance = distance;
    }

    /** A touch goes down; whether it could become a pull. */
    boolean down(float x, float y) {
        armed = y <= edge;
        startX = x;
        startY = y;
        return armed;
    }

    /** The touch moves; true once, when it becomes a pull. */
    boolean move(float x, float y) {
        if (!armed) return false;
        float dy = y - startY;
        if (dy < distance || dy <= Math.abs(x - startX)) return false;
        armed = false;
        return true;
    }

    /** The touch ends (up or cancel). */
    void end() {
        armed = false;
    }
}
