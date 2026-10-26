package com.potkc.overhear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

public class ArtColorTest {
    private static final int WHITE = 0xffffffff;
    private static final int N = ArtColor.SAMPLE_SIDE * ArtColor.SAMPLE_SIDE;

    /** n pixels of colour c, then the rest of a and so on: {count, colour, count, colour ...}. */
    private static int[] pixels(int... countColour) {
        int total = 0;
        for (int i = 0; i < countColour.length; i += 2) total += countColour[i];
        int[] p = new int[total];
        int k = 0;
        for (int i = 0; i < countColour.length; i += 2) {
            for (int j = 0; j < countColour[i]; j++) p[k++] = countColour[i + 1];
        }
        return p;
    }

    private static float[] hsv(int c) {
        float[] h = new float[3];
        ArtColor.rgbToHsv((c >> 16) & 0xff, (c >> 8) & 0xff, c & 0xff, h);
        return h;
    }

    private static void assertHue(float expected, int c) {
        float h = hsv(c)[0];
        float d = Math.abs(h - expected);
        d = Math.min(d, 360 - d);
        assertTrue("hue " + h + " of " + Integer.toHexString(c) + ", expected about " + expected, d <= 12);
    }

    private static void assertLegible(int c) {
        float[] h = hsv(c);
        assertTrue("saturation " + h[1], h[1] <= ArtColor.MAX_SATURATION + 0.01f);
        assertTrue("value " + h[2], h[2] <= ArtColor.TARGET_VALUE + 0.01f);
        assertTrue("luminance " + ArtColor.luminance(c), ArtColor.luminance(c) <= ArtColor.MAX_LUMINANCE);
        assertTrue("white on it", ArtColor.contrast(WHITE, c) >= 5.5);
    }

    @Test public void aSaturatedCoverGivesItsHue() {
        int c = ArtColor.extract(pixels(N, 0xffe02020)); // red
        assertHue(0, c);
        assertLegible(c);
        assertHue(220, ArtColor.extract(pixels(N, 0xff2050e0))); // blue
        assertHue(120, ArtColor.extract(pixels(N, 0xff20c040))); // green
    }

    @Test public void aVividAreaBeatsALargerGreyOne() {
        // 80 % grey background, 20 % magenta: Spotify picks the colour.
        int c = ArtColor.extract(pixels(N * 4 / 5, 0xff808080, N / 5, 0xffd020c0));
        assertHue(306, c);
    }

    @Test public void theLargerVividAreaWins() {
        int c = ArtColor.pick(pixels(N / 3, 0xff2040e0, N * 2 / 3, 0xffe08020));
        assertHue(30, c);
    }

    @Test public void similarShadesShareABin() {
        // Two oranges that would fall in different RGB buckets outweigh one blue.
        int c = ArtColor.pick(pixels(40, 0xffe07020, 40, 0xffd08030, 60, 0xff2040e0));
        assertHue(27, c);
    }

    @Test public void aGreyCoverFallsBackToTheDominantColourAsDarkGrey() {
        int c = ArtColor.extract(pixels(N * 3 / 4, 0xff9a9a9a, N / 4, 0xff303030));
        float[] h = hsv(c);
        assertTrue("grey stays grey: " + h[1], h[1] < 0.05f);
        assertEquals(ArtColor.GREY_VALUE, h[2], 0.01f);
        assertLegible(c);
    }

    @Test public void aMonochromeTintedCoverKeepsItsTintFromTheDominantBucket() {
        // A faded sepia photo: saturation below MIN_SATURATION everywhere, so no vivid pixels.
        int c = ArtColor.pick(pixels(N, 0xff8a8476));
        assertEquals(0xff888878, c); // the bucket centre
        assertTrue("still warm", ((c >> 16) & 0xff) > (c & 0xff));
    }

    @Test public void aFewSpecksDoNotColourAGreyCover() {
        int specks = (int) (N * ArtColor.MIN_VIVID_FRACTION) - 1;
        int c = ArtColor.extract(pixels(N - specks, 0xff707070, specks, 0xffff0000));
        assertTrue(hsv(c)[1] < 0.05f);
    }

    @Test public void aNearBlackCoverGivesDarkNeutralNotAColourFromNoise() {
        int c = ArtColor.extract(pixels(N - 10, 0xff0a0806, 10, 0xff101418));
        float[] h = hsv(c);
        assertTrue("near-black pixels are not vivid even when tinted: " + h[1], h[1] < 0.3f);
        assertTrue(h[2] <= ArtColor.TARGET_VALUE + 0.01f);
        assertLegible(c);
    }

    @Test public void aNearWhiteCoverIsNotWhite() {
        int c = ArtColor.extract(pixels(N, 0xfff8f6f4));
        assertLegible(c);
        assertTrue(ArtColor.luminance(c) <= ArtColor.MAX_LUMINANCE);
        // A white cover with a red title still gives the red.
        assertHue(0, ArtColor.extract(pixels(N - 60, 0xfffafafa, 60, 0xffd01818)));
    }

    @Test public void aPaleBlueCoverGivesAMutedBlueNotGrey() {
        // Like RESCENE's "Busy Boy": a light blue folder on white.
        int c = ArtColor.extract(pixels(N / 3, 0xffb8cfe6, N * 2 / 3, 0xfff4f4f4)); // s 0.2 on white
        assertHue(210, c);
        assertTrue(hsv(c)[1] > ArtColor.GREY_SATURATION);
        assertLegible(c);
    }

    @Test public void pastelsAreNotPickedOverRealColour() {
        int c = ArtColor.pick(pixels(N - 80, 0xfff8e0f0, 80, 0xff2040d0));
        assertHue(228, c);
    }

    @Test public void contrastClamp() {
        // Bright yellow and cyan are light at any value: their value drops further.
        for (int bright : new int[] {0xffffff00, 0xff00ffff, 0xff80ff00, 0xffff00ff, 0xffff0000, 0xff0000ff}) {
            int c = ArtColor.legible(bright);
            assertLegible(c);
            assertTrue(Integer.toHexString(bright) + " keeps some saturation", hsv(c)[1] >= 0.6f);
        }
        float[] h = hsv(ArtColor.legible(0xff0000ff));
        assertEquals("blue is dark enough at the target value", ArtColor.TARGET_VALUE, h[2], 0.01f);
        assertEquals(ArtColor.MAX_SATURATION, h[1], 0.02f);
        assertTrue(hsv(ArtColor.legible(0xffffff00))[2] < ArtColor.TARGET_VALUE);
    }

    @Test public void dullColoursKeepTheirSaturation() {
        float[] h = hsv(ArtColor.legible(0xff806050));
        assertEquals(hsv(0xff806050)[1], h[1], 0.03f);
    }

    @Test public void transparentPixelsAreIgnoredAndNoneGivesNeutral() {
        assertEquals(ArtColor.NEUTRAL, ArtColor.extract(new int[0]));
        assertEquals(ArtColor.NEUTRAL, ArtColor.extract(pixels(N, 0x00ff0000)));
        assertHue(220, ArtColor.extract(pixels(N, 0x00ff0000, 20, 0xff2050e0)));
    }

    @Test public void deterministic() {
        Random r = new Random(42);
        int[] p = new int[N];
        for (int i = 0; i < N; i++) p[i] = 0xff000000 | r.nextInt(0x1000000);
        int first = ArtColor.extract(p);
        for (int i = 0; i < 20; i++) assertEquals(first, ArtColor.extract(p.clone()));
        // Ties go to the lower hue bin, whatever the pixel order.
        int[] tie = pixels(50, 0xffe02020, 50, 0xff2050e0);
        int[] reversed = pixels(50, 0xff2050e0, 50, 0xffe02020);
        assertEquals(ArtColor.extract(tie), ArtColor.extract(reversed));
        assertHue(0, ArtColor.extract(tie));
    }

    @Test public void textOverTheGradientStaysLegible() {
        // Where the player's text sits (fractions of the 480 px height), on the
        // gradient of the brightest legible colours: the clock (white) at the
        // top, the title and subtitle (white), the seek times and the status
        // line (grey #B3B3B3) lower down.
        int[] colours = {0xffffff00, 0xff00ffff, 0xff80ff00, 0xffff00ff, 0xffff0000, 0xff0000ff, 0xfff8f8f8};
        for (int raw : colours) {
            int[] g = ArtColor.gradient(ArtColor.legible(raw), ArtColor.BACKGROUND);
            assertTrue(ArtColor.contrast(WHITE, at(g, 0.02f)) >= 4.5);
            assertTrue(ArtColor.contrast(WHITE, at(g, 0.60f)) >= 4.5);
            assertTrue(ArtColor.contrast(WHITE, at(g, 0.65f)) >= 4.5);
            assertTrue(ArtColor.contrast(0xffb3b3b3, at(g, 0.76f)) >= 3.0);
            assertTrue(ArtColor.contrast(0xffb3b3b3, at(g, 0.96f)) >= 3.0);
        }
    }

    /** The colour at fraction y of the height, as GradientDrawable draws evenly spaced stops. */
    private static int at(int[] g, float y) {
        float p = y * (g.length - 1);
        int i = Math.min(g.length - 2, (int) p);
        return ArtColor.mix(g[i], g[i + 1], p - i);
    }

    @Test public void gradientFadesToTheBackgroundByFadeEnd() {
        int top = 0xff6a2a2a;
        int[] g = ArtColor.gradient(top, ArtColor.BACKGROUND);
        assertEquals(ArtColor.GRADIENT_STOPS, g.length);
        assertEquals(top, g[0]);
        for (int i = 0; i < g.length; i++) {
            float pos = i / (float) (g.length - 1);
            if (pos >= ArtColor.FADE_END) assertEquals("stop " + i, ArtColor.BACKGROUND, g[i]);
        }
        for (int i = 1; i < g.length; i++) {
            assertTrue("monotonic", ((g[i] >> 16) & 0xff) <= ((g[i - 1] >> 16) & 0xff));
        }
        int[] flat = new int[ArtColor.GRADIENT_STOPS];
        Arrays.fill(flat, ArtColor.BACKGROUND);
        assertArrayEquals("no art: plain background", flat, ArtColor.gradient(ArtColor.BACKGROUND, ArtColor.BACKGROUND));
    }

    @Test public void downscaleBoxAverages() {
        // A 4x4 of four 2x2 quadrants, one colour each.
        int[] img = new int[16];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                img[y * 4 + x] = y < 2 ? (x < 2 ? 0xffff0000 : 0xff00ff00) : (x < 2 ? 0xff0000ff : 0xff000000);
            }
        }
        assertArrayEquals(new int[] {0xffff0000, 0xff00ff00, 0xff0000ff, 0xff000000}, ArtColor.downscale(img, 4, 4, 2));
        // A checkerboard averages to grey instead of aliasing to one of its colours.
        int[] checker = new int[240 * 240];
        for (int i = 0; i < checker.length; i++) checker[i] = ((i / 240 + i % 240) & 1) == 0 ? WHITE : 0xff000000;
        for (int p : ArtColor.downscale(checker, 240, 240, 24)) assertEquals(0xff808080, p);
        // Every source pixel is counted: a non-multiple size keeps the mean.
        int[] odd = new int[7 * 5];
        Arrays.fill(odd, 0xff336699);
        for (int p : ArtColor.downscale(odd, 7, 5, 3)) assertEquals(0xff336699, p);
    }

    @Test public void hsvRoundTrips() {
        for (int c : new int[] {0xffff0000, 0xff00ff00, 0xff0000ff, 0xff808080, 0xff123456, 0xfffedcba}) {
            float[] h = hsv(c);
            int back = ArtColor.hsvToRgb(h[0], h[1], h[2]);
            for (int shift = 0; shift <= 16; shift += 8) {
                assertEquals(Integer.toHexString(c), (c >> shift) & 0xff, (back >> shift) & 0xff, 1);
            }
        }
    }
}
