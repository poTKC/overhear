package jp.tkc.st15iplayer;

/**
 * The player's background colour from a cover, like Spotify's: the cover's
 * most prominent reasonably saturated colour, darkened so white text and
 * the white controls keep strong contrast. Soloist's metadata has no colour,
 * so this runs on the device on a tiny downscale of the decoded cover
 * (SAMPLE_SIDE square, box-averaged by downscale(): Bitmap.createScaledBitmap
 * is bilinear and, at 10x, samples only a few source pixels, which made the
 * colour depend on where the grid fell), on the artwork thread. Pure Java
 * (host tests).
 *
 * Picking: opaque pixels are put into HUE_BINS hue bins when their HSV
 * saturation is at least MIN_SATURATION and value at least MIN_VALUE (greys,
 * near-black and near-white do not count), each pixel weighted by its
 * saturation, so a small vivid area can beat a large dull one but not a large
 * vivid one. Each non-empty bin is scored with its two neighbours' weight
 * (so a colour on a bin edge is not split in two); the best score wins (the
 * lower bin on a tie) and the weighted mean RGB of those three bins is the
 * colour. If the vivid pixels are fewer than
 * MIN_VIVID_FRACTION of the opaque ones (a monochrome or grey cover), the
 * most populous 4-bit-per-channel RGB bucket is used instead; with no opaque
 * pixels at all, NEUTRAL.
 *
 * Legibility (tuned on the ST15i with real covers, see the design doc):
 * saturation is clamped to MAX_SATURATION, value set to TARGET_VALUE
 * (GREY_VALUE for a colour that is nearly grey), then value lowered until the
 * relative luminance is at most MAX_LUMINANCE, which keeps white at a
 * contrast of at least (1.05 / (MAX_LUMINANCE + 0.05)) : 1.
 *
 * Deterministic: integer accumulation in a fixed order.
 */
final class ArtColor {
    /** The player's own background (activity_player.xml), where the gradient ends. */
    static final int BACKGROUND = 0xff101010;
    /** When a cover has no usable pixels: the dark grey legible() gives any grey. */
    static final int NEUTRAL = 0xff616161;
    /** The side of the downscaled cover the Android side samples. */
    static final int SAMPLE_SIDE = 24;

    static final int HUE_BINS = 24;
    static final float MIN_SATURATION = 0.18f;
    static final float MIN_VALUE = 0.22f;
    /** Whites (s low anyway) and blown-out pastels: v above this with s below PASTEL_SATURATION. */
    static final float MAX_PASTEL_VALUE = 0.95f;
    static final float PASTEL_SATURATION = 0.35f;
    static final float MIN_VIVID_FRACTION = 0.04f;

    static final float MAX_SATURATION = 0.65f;
    static final float TARGET_VALUE = 0.52f;
    static final float GREY_SATURATION = 0.12f;
    static final float GREY_VALUE = 0.38f;
    static final double MAX_LUMINANCE = 0.14;

    /** Where the gradient reaches BACKGROUND, as a fraction of the height. */
    static final float FADE_END = 0.78f;
    /** Colour stops of the gradient (GradientDrawable spaces them evenly). */
    static final int GRADIENT_STOPS = 15;

    private ArtColor() {}

    /**
     * Box-averages a w x h ARGB image (row-major) into side x side: every
     * source pixel counts once, in the cell its centre falls in.
     */
    static int[] downscale(int[] argb, int w, int h, int side) {
        long[] a = new long[side * side], r = new long[side * side], g = new long[side * side],
                b = new long[side * side];
        int[] n = new int[side * side];
        for (int y = 0; y < h; y++) {
            int cy = (int) ((y * 2L + 1) * side / (2L * h));
            for (int x = 0; x < w; x++) {
                int cx = (int) ((x * 2L + 1) * side / (2L * w));
                int i = cy * side + cx;
                int p = argb[y * w + x];
                a[i] += p >>> 24;
                r[i] += (p >> 16) & 0xff;
                g[i] += (p >> 8) & 0xff;
                b[i] += p & 0xff;
                n[i]++;
            }
        }
        int[] out = new int[side * side];
        for (int i = 0; i < out.length; i++) {
            int k = n[i];
            if (k == 0) continue; // a source smaller than side: transparent
            out[i] = (int) ((a[i] + k / 2) / k) << 24 | (int) ((r[i] + k / 2) / k) << 16
                    | (int) ((g[i] + k / 2) / k) << 8 | (int) ((b[i] + k / 2) / k);
        }
        return out;
    }

    /** The background colour for a cover's ARGB pixels (any order, any count). */
    static int extract(int[] argb) {
        return legible(pick(argb));
    }

    /** The raw prominent colour, before the legibility adjustment. */
    static int pick(int[] argb) {
        long[] weight = new long[HUE_BINS];
        long[] sr = new long[HUE_BINS];
        long[] sg = new long[HUE_BINS];
        long[] sb = new long[HUE_BINS];
        int[] buckets = new int[4096];
        int opaque = 0;
        int vivid = 0;
        float[] hsv = new float[3];
        for (int p : argb) {
            if ((p >>> 24) < 128) continue;
            opaque++;
            int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
            buckets[((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4)]++;
            rgbToHsv(r, g, b, hsv);
            float s = hsv[1], v = hsv[2];
            if (s < MIN_SATURATION || v < MIN_VALUE) continue;
            if (v > MAX_PASTEL_VALUE && s < PASTEL_SATURATION) continue;
            vivid++;
            int bin = Math.min(HUE_BINS - 1, (int) (hsv[0] / 360f * HUE_BINS));
            long w = Math.round(s * 1000);
            weight[bin] += w;
            sr[bin] += r * w;
            sg[bin] += g * w;
            sb[bin] += b * w;
        }
        if (opaque == 0) return NEUTRAL;
        if (vivid > 0 && vivid >= MIN_VIVID_FRACTION * opaque) {
            int best = -1;
            long bestScore = -1;
            for (int i = 0; i < HUE_BINS; i++) {
                long score = weight[i] + weight[(i + HUE_BINS - 1) % HUE_BINS] + weight[(i + 1) % HUE_BINS];
                if (weight[i] > 0 && score > bestScore) {
                    best = i;
                    bestScore = score;
                }
            }
            long w = 0, r = 0, g = 0, b = 0;
            for (int d = -1; d <= 1; d++) {
                int i = (best + d + HUE_BINS) % HUE_BINS;
                w += weight[i];
                r += sr[i];
                g += sg[i];
                b += sb[i];
            }
            return rgb((int) ((r + w / 2) / w), (int) ((g + w / 2) / w), (int) ((b + w / 2) / w));
        }
        // Monochrome or grey: the dominant colour (the bucket's centre).
        int best = 0;
        for (int i = 1; i < buckets.length; i++) {
            if (buckets[i] > buckets[best]) best = i;
        }
        return rgb(((best >> 8) << 4) | 8, (((best >> 4) & 15) << 4) | 8, ((best & 15) << 4) | 8);
    }

    /** Clamps saturation, sets a dark value and caps the luminance (see the class comment). */
    static int legible(int color) {
        float[] hsv = new float[3];
        rgbToHsv((color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff, hsv);
        float s = Math.min(hsv[1], MAX_SATURATION);
        float v = s < GREY_SATURATION ? GREY_VALUE : TARGET_VALUE;
        int c = hsvToRgb(hsv[0], s, v);
        while (luminance(c) > MAX_LUMINANCE && v > 0.05f) {
            v -= 0.01f;
            c = hsvToRgb(hsv[0], s, v);
        }
        return c;
    }

    /**
     * The gradient's colour stops, top to bottom: top easing into bottom
     * until FADE_END of the height, then bottom.
     */
    static int[] gradient(int top, int bottom) {
        int[] c = new int[GRADIENT_STOPS];
        for (int i = 0; i < GRADIENT_STOPS; i++) {
            float pos = i / (float) (GRADIENT_STOPS - 1);
            float t = Math.min(1f, pos / FADE_END);
            // Ease in-out: the colour holds near the top and lands softly.
            t = t * t * (3 - 2 * t);
            c[i] = mix(top, bottom, t);
        }
        return c;
    }

    /** a at t = 0, b at t = 1, per channel (opaque). */
    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xff) + (((b >> 16) & 0xff) - ((a >> 16) & 0xff)) * t);
        int g = Math.round(((a >> 8) & 0xff) + (((b >> 8) & 0xff) - ((a >> 8) & 0xff)) * t);
        int bl = Math.round((a & 0xff) + ((b & 0xff) - (a & 0xff)) * t);
        return rgb(r, g, bl);
    }

    /** WCAG relative luminance, 0..1. */
    static double luminance(int c) {
        return 0.2126 * linear((c >> 16) & 0xff) + 0.7152 * linear((c >> 8) & 0xff) + 0.0722 * linear(c & 0xff);
    }

    /** WCAG contrast ratio of two opaque colours, 1..21. */
    static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** hsv: hue 0..360, saturation and value 0..1 (like android.graphics.Color). */
    static void rgbToHsv(int r, int g, int b, float[] hsv) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = 60f * (((g - b) / (float) d) % 6f);
        else if (max == g) h = 60f * ((b - r) / (float) d + 2f);
        else h = 60f * ((r - g) / (float) d + 4f);
        if (h < 0) h += 360f;
        hsv[0] = h;
        hsv[1] = max == 0 ? 0 : d / (float) max;
        hsv[2] = max / 255f;
    }

    static int hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float hp = (h % 360f) / 60f;
        float x = c * (1 - Math.abs(hp % 2f - 1));
        float r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; }
        else if (hp < 2) { r = x; g = c; }
        else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; }
        else if (hp < 5) { r = x; b = c; }
        else { r = c; b = x; }
        float m = v - c;
        return rgb(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }

    private static int rgb(int r, int g, int b) {
        return 0xff000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int x) {
        return x < 0 ? 0 : x > 255 ? 255 : x;
    }
}
