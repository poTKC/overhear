package com.potkc.overhear.spotify;

import java.util.List;

/**
 * Part of a list. next is what the following page needs: the offset for
 * offset paging, the cursor for followed artists; null on the last page.
 * Pure Java.
 */
public final class Page {
    public final List<Item> items;
    public final String next;

    public Page(List<Item> items, String next) {
        this.items = items;
        this.next = next;
    }
}
