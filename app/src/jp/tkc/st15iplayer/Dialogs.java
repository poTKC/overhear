package jp.tkc.st15iplayer;

import android.app.AlertDialog;
import android.view.View;
import android.widget.TextView;

/** Small fixes for the app's AlertDialogs. */
final class Dialogs {
    static final int ACCENT = 0xff1db954;

    private Dialogs() {}

    /**
     * Turns the title divider green: alert_dialog_holo.xml (API 18) gives it a
     * fixed Holo blue background, out of reach of the theme. The title text is
     * green through the theme; it is set here too in case a ROM ignores it.
     * Call after show().
     */
    static AlertDialog green(AlertDialog d) {
        View divider = find(d, "titleDivider");
        if (divider != null) divider.setBackgroundColor(ACCENT);
        View title = find(d, "alertTitle");
        if (title instanceof TextView) ((TextView) title).setTextColor(ACCENT);
        return d;
    }

    private static View find(AlertDialog d, String name) {
        int id = d.getContext().getResources().getIdentifier(name, "id", "android");
        return id == 0 ? null : d.findViewById(id);
    }
}
