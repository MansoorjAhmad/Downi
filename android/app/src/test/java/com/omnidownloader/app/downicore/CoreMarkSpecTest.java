package com.omnidownloader.app.downicore;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The mark's size spec — and the guard that the shipping asset is still the sheet's mark.
 *
 * Phase A's visual half is device-gated (cells K-A1…K-A5), but the two ways this can silently break
 * are not:
 *   * the scale (0.63 of the inner disc) could drift away from the sheets' own 0.53 disc ratio, or
 *   * the asset could be swapped — the owner's complaint was exactly that (the *app* icon drawn
 *     inside the Core), which would leave the arithmetic perfectly correct and the mark wrong.
 * Both are asserted here, on the JVM, with no phone attached.
 */
public class CoreMarkSpecTest {

    private static final float EPS = 0.0005f;

    /** The lifted mark, byte for byte — see {@link #shippingAssetIsTheLiftedSheetMark}. */
    private static final String MARK_SHA256 =
            "bf4382849bd9904bdf7f22b04760e32403e783821c9df3df6d81a6a13a395beb";
    private static final String MARK_DARK_SHA256 =
            "166103aeedf2b004a1280c388c8bc87870114912ce191b65ba99142cf861b531";

    /** The baked scale *is* the sheets' ratio, best-fitted across the sizes that ship.
     *  0.65 (was 0.63 before the master-package visual pass: the visible pebble is now 0.80 of
     *  its window (§6 small visual / §19 full touch target), so the fixed dp insets are a larger
     *  share of a smaller disc and the best fit moved up one notch). */
    private static final float BAKED_SCALE = 0.65f;

    @Test public void bakedScaleIsTheSheetsOwnRatio() {
        assertEquals("shipping tile scale", BAKED_SCALE, CoreLook.MARK_SCALE, EPS);
        assertEquals("the solve must be idempotent", CoreLook.MARK_SCALE,
                CoreLook.markScaleFor(CoreLook.SHEET_MARK_DISC_RATIO), EPS);
    }

    /** No two-decimal neighbour fits the shipping sizes better — the bake is a best fit, not a guess. */
    @Test public void noNeighbouringScaleFitsBetter() {
        float mine = CoreLook.markWorstCaseError(CoreLook.MARK_SCALE);
        assertTrue("the neighbour below must be worse",
                CoreLook.markWorstCaseError(CoreLook.MARK_SCALE - 0.01f) > mine);
        assertTrue("the neighbour above must be worse",
                CoreLook.markWorstCaseError(CoreLook.MARK_SCALE + 0.01f) > mine);
        assertTrue("every shipping size must stay near the sheet (is " +
                mine + ")", mine <= 0.015f);
    }

    /** Every size the Core ships at keeps the mark near the sheets' ratio.
     *  Tolerance 1.5%: with the pebble at 0.80 of the window, the fixed dp insets swing more per
     *  size than the old full-window disc — ±1.3% worst at the smallest visual is the same
     *  achievement the old geometry made at ±1%. */
    @Test public void everyShippingSizeStaysNearTheSheetRatio() {
        for (int dp : CoreLook.SIZES_DP) {
            float ratio = CoreLook.markDiscRatio(CoreLook.MARK_SCALE, dp);
            assertTrue("at " + dp + " dp the mark draws " + ratio + " of the disc, the sheet says "
                    + CoreLook.SHEET_MARK_DISC_RATIO,
                    Math.abs(ratio - CoreLook.SHEET_MARK_DISC_RATIO) <= 0.015f);
            assertTrue("a bigger Core must not draw a relatively smaller mark", ratio > 0.5f);
        }
    }

    /** The Core's sizes and the pure spec cannot drift apart. */
    @Test public void sizesMatchTheWindowHost() {
        assertArrayEquals(DowniCore.SIZES_DP, CoreLook.SIZES_DP);
    }

    /**
     * The two numbers the size math multiplies by are the *measured* ones, not round numbers: the
     * visible glyph fills 458 x 357 px of its 512 px tile. Pinning them means an accidental edit
     * fails loudly and has to be re-justified against the asset
     * (`python tools/core_mark_measure.py asset`).
     */
    @Test public void recordedGeometryIsTheMeasurement() {
        assertEquals(458 / 512f, CoreLook.MARK_TILE_HEIGHT, EPS);
        assertEquals(357 / 512f, CoreLook.MARK_TILE_WIDTH, EPS);
        assertTrue("the mark is taller than it is wide",
                CoreLook.MARK_TILE_HEIGHT > CoreLook.MARK_TILE_WIDTH);
        assertTrue("one tile scale has to serve every shipping size", CoreLook.SIZES_DP.length >= 3);
    }

    /**
     * The asset on disk is the mark the owner approved — the same bytes.
     *
     * A PNG header check plus a SHA-256 of the file is the cheapest guard there is, and it is the
     * one that matters: the failure being guarded against is not a wrong number, it is a *swapped
     * asset* (a session drew the speed-D app icon inside the Core, and nothing failed until the
     * owner looked at it). If the mark is ever re-lifted on purpose, this digest changes on purpose,
     * with that sheet's evidence.
     */
    @Test public void shippingAssetIsTheLiftedSheetMark() throws IOException {
        File png = findAsset("downi_core_mark.png");
        assumeTrue("asset not found in this checkout - run from the module or the repo root", png != null);
        assertPng(png, 154846, MARK_SHA256);
    }

    /** The graphite variant is the same tile with the same geometry — other palette, same lift. */
    @Test public void darkVariantIsTheSameTileInGraphite() throws IOException {
        File png = findAsset("downi_core_mark_dark.png");
        assumeTrue("asset not found in this checkout", png != null);
        assertPng(png, 137388, MARK_DARK_SHA256);
    }

    private static void assertPng(File png, int bytes, String digest) throws IOException {
        assertEquals("asset size", bytes, (int) png.length());
        int[] h = readPngHeader(png);
        assertEquals("master width", 512, h[0]);
        assertEquals("master height", 512, h[1]);
        assertEquals("8-bit", 8, h[2]);
        assertEquals("RGBA - the mark is a transparent tile, the app icon is not", 6, h[3]);
        assertEquals("the mark must not be swapped for another drawing", digest, sha256(png));
    }

    /** width, height, bit depth, colour type — straight out of the PNG's IHDR chunk (no AWT here). */
    private static int[] readPngHeader(File f) throws IOException {
        byte[] b = new byte[26];
        FileInputStream in = new FileInputStream(f);
        try {
            assertEquals("a PNG header is 26 bytes", 26, in.read(b));
        } finally {
            in.close();
        }
        assertEquals("png signature", 0x89, b[0] & 0xFF);
        assertEquals('P', b[1] & 0xFF);
        assertEquals('N', b[2] & 0xFF);
        assertEquals('G', b[3] & 0xFF);
        return new int[]{be32(b, 16), be32(b, 20), b[24] & 0xFF, b[25] & 0xFF};
    }

    private static int be32(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            } finally {
                in.close();
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    /** The asset lives in drawable-nodpi, so it is never density-scaled by the framework. */
    private static File findAsset(String name) {
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; up < 7 && dir != null; up++) {
            for (String rel : new String[]{
                    "src/main/res/drawable-nodpi/",
                    "app/src/main/res/drawable-nodpi/",
                    "android/app/src/main/res/drawable-nodpi/"}) {
                File f = new File(dir, rel + name);
                if (f.isFile()) return f;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
