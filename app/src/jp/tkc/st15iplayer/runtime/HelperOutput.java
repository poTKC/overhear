package jp.tkc.st15iplayer.runtime;

/**
 * Parses st15i-fetch's stdout protocol: "progress N" (0-100), "extracting"
 * and a final "done". Anything else is ignored. Pure Java so it runs in the
 * host tests.
 */
final class HelperOutput {
    private final ProgressListener listener;
    private boolean done;
    private int lastPercent = -1;

    /** listener may be null. */
    HelperOutput(ProgressListener listener) {
        this.listener = listener;
    }

    void line(String raw) {
        String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
        if (line.equals("done")) {
            done = true;
        } else if (line.equals("extracting")) {
            if (listener != null) listener.onExtracting();
        } else if (line.startsWith("progress ")) {
            String n = line.substring("progress ".length());
            if (n.isEmpty() || n.length() > 3) return;
            for (int i = 0; i < n.length(); i++) {
                if (n.charAt(i) < '0' || n.charAt(i) > '9') return;
            }
            int pct = Integer.parseInt(n);
            if (pct > 100) return;
            lastPercent = pct;
            if (listener != null) listener.onProgress(pct);
        }
    }

    /** True once "done" was seen: the helper finished successfully. */
    boolean isDone() {
        return done;
    }

    int lastPercent() {
        return lastPercent;
    }
}
