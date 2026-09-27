package com.omnidownloader.app.downicore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Fetcher 2.0's Core art — the guard that the shipping material is the owner's sheet, at the right
 * size, and that swapping it did not move the Core.
 *
 * The old mark test asserted a *scale*; this one has to assert something narrower, because the
 * material is no longer drawn from numbers at all. Three failure modes remain possible and all three
 * are checked here, on the JVM, with no phone attached:
 *
 *   · a swapped or regenerated asset — caught by the byte size + colour type + SHA-256 pins, the
 *     same "no silent wrong drawing" rule the mark test uses;
 *   · a wrong object/tile ratio — the number that decides whether the art's disc lands on the Core's
 *     disc, measured by `python tools/core_sheet_extract.py` and pinned here;
 *   · a Core whose disc moved when the material changed — asserted directly: the art's disc must be
 *     exactly the geometry Fetcher 1.0 drew.
 */
public class CoreArtSpecTest {

    private static final float EPS = 0.0005f;

    /** The Core's own art and its paused variant, byte for byte (see shippingArtIsTheSheetsOrb). */
    private static final String ORB_SHA256 =
            "00b68bf7ce7035075035d7cb8f6f243c3c8624d0d87ae6b44c1e1c5ca34665b0";
    private static final String ORB_PAUSED_SHA256 =
            "6c45faf9e6c13e2ff160d2c1131929f059f18f5e76ab5c947fde3b57c3e0572d";

    /**
     * The asset on disk is the orb `tools/core_sheet_extract.py` lifted from C1 — the same bytes.
     * If the Core is ever re-lifted on purpose (a new sheet, a re-crop after a design change), this
     * digest changes on purpose, together with §8 of the v3.3 plan.
     */
    @Test public void shippingArtIsTheSheetsOrb() throws IOException {
        File orb = findAsset("core_orb.png");
        File paused = findAsset("core_orb_paused.png");
        assumeTrue("art not found in this checkout - run from the module or the repo root",
                orb != null && paused != null);
        assertPng(orb, 224737, ORB_SHA256);
        assertPng(paused, 152993, ORB_PAUSED_SHA256);
        assertNotEquals("the paused art must not be the idle art renamed",
                sha256(orb), sha256(paused));
    }

    /** The two ratios are the ones the extractor printed, not round numbers and not each other. */
    @Test public void tileRatiosAreTheMeasuredOnes() {
        assertEquals("core_orb.png object/tile", 0.8606f, CoreLook.ART_TILE_RATIO, 0.00005f);
        assertEquals("core_orb_paused.png object/tile", 0.7799f,
                CoreLook.ART_TILE_RATIO_PAUSED, 0.00005f);
        assertTrue("C1's state row keeps more glow padding than the hero crop",
                CoreLook.ART_TILE_RATIO_PAUSED < CoreLook.ART_TILE_RATIO);
        assertEquals("the ratio is chosen by which art is drawn",
                CoreLook.ART_TILE_RATIO_PAUSED, CoreLook.artTileRatio(true), EPS);
        assertEquals(CoreLook.ART_TILE_RATIO, CoreLook.artTileRatio(false), EPS);
    }

    /**
     * The art's DISC is the disc Fetcher 1.0 drew — the material changed, the size did not.
     * {@code side * ratio} has to equal the old 2r exactly at 48, 56 and 64 dp.
     */
    @Test public void theDiscDidNotMove() {
        for (int dp : CoreLook.SIZES_DP) {
            float r = (dp / 2f - CoreLook.DISC_INSET_DP) * CoreLook.VISUAL_IN_WINDOW;
            for (float ratio : new float[]{CoreLook.ART_TILE_RATIO, CoreLook.ART_TILE_RATIO_PAUSED}) {
                float disc = CoreLook.artTileSideDp(dp, ratio) * ratio;
                assertEquals("disc diameter at " + dp + " dp (ratio " + ratio + ")", 2f * r, disc, EPS);
            }
        }
    }

    /** The tile (which includes the glow's room) still fits the window at every shipping size. */
    @Test public void theArtFitsTheWindow() {
        for (int dp : CoreLook.SIZES_DP) {
            for (float ratio : new float[]{CoreLook.ART_TILE_RATIO, CoreLook.ART_TILE_RATIO_PAUSED}) {
                float side = CoreLook.artTileSideDp(dp, ratio);
                assertTrue("tile " + side + " dp must fit the " + dp + " dp window",
                        side <= dp - 4f);
                assertTrue("the tile is the visible pebble, never the whole window", side < dp);
            }
        }
    }

    /** The geometry constants the view and the table share cannot drift apart. */
    @Test public void geometryConstantsMatchTheView() {
        assertEquals("the visible fraction lives in two files on purpose; they must agree",
                CoreLook.VISUAL_IN_WINDOW, CoreHost.VISUAL_IN_WINDOW, EPS);
        assertEquals("the glow inset is what the disc inset was", 6f, CoreLook.DISC_INSET_DP, EPS);
        assertTrue("a Core must still read at the smallest size",
                CoreLook.artTileSideDp(48, CoreLook.ART_TILE_RATIO) > 24f);
    }

    private static void assertPng(File png, int bytes, String digest) throws IOException {
        assertEquals("asset size", bytes, (int) png.length());
        int[] h = readPngHeader(png);
        assertEquals("master width", 512, h[0]);
        assertEquals("master height", 512, h[1]);
        assertEquals("8-bit", 8, h[2]);
        assertEquals("RGBA - the art is a transparent tile, the app icon is not", 6, h[3]);
        assertEquals("the Core must not be swapped for another drawing", digest, sha256(png));
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

    /** The art lives in drawable-nodpi, so it is never density-scaled by the framework. */
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
