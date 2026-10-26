package com.potkc.overhear;

import android.app.AlertDialog;
import android.view.View;
import android.widget.TextView;

/** Small fixes for the app's AlertDialogs. */
final class Dialogs {
    /** The setup screen's dialogs: no accent colour. */
    static final int TITLE = 0xffffffff;
    static final int DIVIDER = 0xffe0e0e0;

    private Dialogs() {}

    /**
     * Turns the title divider light grey: alert_dialog_holo.xml (API 18) gives
     * it a fixed Holo blue background, out of reach of the theme. The title
     * text is white through the theme; it is set here too in case a ROM
     * ignores it. Call after show().
     */
    static AlertDialog neutral(AlertDialog d) {
        View divider = find(d, "titleDivider");
        if (divider != null) divider.setBackgroundColor(DIVIDER);
        View title = find(d, "alertTitle");
        if (title instanceof TextView) ((TextView) title).setTextColor(TITLE);
        return d;
    }

    private static View find(AlertDialog d, String name) {
        int id = d.getContext().getResources().getIdentifier(name, "id", "android");
        return id == 0 ? null : d.findViewById(id);
    }
}
