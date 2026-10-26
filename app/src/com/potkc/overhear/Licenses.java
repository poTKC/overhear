package com.potkc.overhear;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Shows the bundled third-party notices (assets/licenses, written by
 * runtime/build.sh). The text is ~140 KB: one TextView would lay it all out
 * on the main thread (about 20 s on the ST15i), so each paragraph is a list
 * row and only the visible ones are laid out.
 */
final class Licenses {
    static final String ASSET = "licenses/THIRD_PARTY_NOTICES.txt";

    private Licenses() {}

    static void show(final Activity a) {
        String text;
        try {
            text = read(a.getAssets().open(ASSET));
        } catch (IOException e) {
            text = a.getString(R.string.licenses_missing);
        }
        final String[] paragraphs = text.split("\n[ \t]*\n");
        final int pad = Math.round(8 * a.getResources().getDisplayMetrics().density);
        ListView list = new ListView(a);
        list.setDivider(null);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() {
                return paragraphs.length;
            }

            @Override public Object getItem(int position) {
                return paragraphs[position];
            }

            @Override public long getItemId(int position) {
                return position;
            }

            @Override public boolean isEnabled(int position) {
                return false;
            }

            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) convertView;
                if (tv == null) {
                    tv = new TextView(a);
                    tv.setTextSize(10);
                    tv.setPadding(pad, pad / 2, pad, pad / 2);
                }
                tv.setText(paragraphs[position]);
                return tv;
            }
        });
        Dialogs.neutral(new AlertDialog.Builder(a)
                .setTitle(R.string.licenses)
                .setView(list)
                .setPositiveButton(R.string.close, null)
                .show());
    }

    private static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
