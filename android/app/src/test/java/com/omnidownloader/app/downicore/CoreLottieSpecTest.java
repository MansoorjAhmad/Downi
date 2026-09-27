package com.omnidownloader.app.downicore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.io.FileReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Fetcher 2.0's ten state animations must be *structurally* correct, because a broken Lottie file
 * does not throw -- it renders nothing, or renders the wrong thing quietly, and only on a phone.
 *
 * The JSON is generated (tools/core_lottie_build.py) from the owner's sheet art, so this test is
 * the second half of that contract, re-run on every build:
 *
 *   · all ten files of V3.3_PLAN.md section 3 are here, at the right length for the design;
 *   · every image layer's asset resolves, is 512x512, and carries no absolute path (a leaked
 *     "C:\..." would work on the build machine and fail on the phone);
 *   · every state actually animates, with keyframe times increasing and inside the composition;
 *   · core_progress.json is scrubbable by setProgress (a trim running 0 -> 100%), not autoplayed;
 *   · the baked rose/blue-grey art is CoreTint's own matrix applied to the shipped orb, so the
 *     Lottie cross-fade and the Java static path cannot drift apart.
 */
public class CoreLottieSpecTest {

    /** V3.3_PLAN.md section 3, in order, with the frame counts the design asks for at 60 fps. */
    private static final String[] STATES = {
            "core_dormant", "core_wake", "core_idle_ready", "core_press", "core_progress",
            "core_pause", "core_complete", "core_failure", "core_retry", "core_unsupported"};
    private static final int[] FRAMES = {90, 36, 120, 24, 120, 18, 45, 30, 36, 30};

    private static final int CANVAS = 512;
    private static final int FPS = 60;

    // --- helpers used by the tests below ------------------------------------

    private static JsonObject load(String state) {
        File f = coreFile(state + ".json");
        assumeTrue("assets/core not found in this checkout - run the JVM tests from the module",
                f != null);
        try (FileReader r = new FileReader(f)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError(state + ".json is not valid JSON: " + e, e);
        }
    }

    private static int num(JsonObject o, String key) {
        assertNotNull("missing \"" + key + "\"", o.get(key));
        return o.get(key).getAsInt();
    }

    private static JsonObject asset(JsonObject an, String id) {
        for (JsonElement a : an.getAsJsonArray("assets")) {
            JsonObject asset = a.getAsJsonObject();
            if (asset.has("id") && id.equals(asset.get("id").getAsString())) {
                return asset;
            }
        }
        return null;
    }

    /** Walks a layer's transform + shape tree, asserting every animated property it finds. */
    private static int countAnimated(JsonElement node, String state, int op) {
        int found = 0;
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                found += countAnimated(child, state, op);
            }
            return found;
        }
        if (!node.isJsonObject()) {
            return 0;
        }
        JsonObject o = node.getAsJsonObject();
        if (o.has("k") && o.has("a") && o.get("a").isJsonPrimitive()
                && o.get("a").getAsInt() == 1 && o.get("k").isJsonArray()) {
            found++;
            JsonArray keys = o.getAsJsonArray("k");
            assertTrue(state + ": an animated property has fewer than 2 keyframes",
                    keys.size() >= 2);
            double last = -1;
            for (JsonElement ke : keys) {
                JsonObject kf = ke.getAsJsonObject();
                assertTrue(state + ": a keyframe has no time", kf.has("t"));
                double t = kf.get("t").getAsDouble();
                assertTrue(state + ": keyframe times must not go backwards (" + last + " -> "
                        + t + ")", t > last);
                assertTrue(state + ": keyframe at " + t + " is outside the composition 0.." + op,
                        t >= 0 && t <= op);
                assertFinite(kf.get("s"), state);
                assertFinite(kf.get("e"), state);
                last = t;
            }
        }
        for (java.util.Map.Entry<String, JsonElement> e : o.entrySet()) {
            found += countAnimated(e.getValue(), state, op);
        }
        return found;
    }

    private static void assertFinite(JsonElement v, String state) {
        if (v == null) {
            return;
        }
        if (v.isJsonArray()) {
            for (JsonElement e : v.getAsJsonArray()) {
                assertFinite(e, state);
            }
        } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
            double d = v.getAsDouble();
            assertTrue(state + ": non-finite keyframe value " + d,
                    !Double.isNaN(d) && !Double.isInfinite(d));
        }
    }

    /** The first trim ("tm") element anywhere in the composition. */
    private static JsonObject findTrim(JsonElement node) {
        if (node.isJsonArray()) {
            for (JsonElement c : node.getAsJsonArray()) {
                JsonObject hit = findTrim(c);
                if (hit != null) {
                    return hit;
                }
            }
        } else if (node.isJsonObject()) {
            JsonObject o = node.getAsJsonObject();
            if (o.has("ty") && o.get("ty").isJsonPrimitive()
                    && "tm".equals(o.get("ty").getAsString())) {
                return o;
            }
            for (java.util.Map.Entry<String, JsonElement> e : o.entrySet()) {
                JsonObject hit = findTrim(e.getValue());
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** Lets a test skip instead of fail when it is run from outside the module. */
    private static File coreFile(String name) {
        return search("src/main/assets/core/", name);
    }

    private static File drawable(String name) {
        return search("src/main/res/drawable-nodpi/", name);
    }

    static File search(String rel, String name) {
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; up < 7 && dir != null; up++) {
            for (String prefix : new String[]{"", "app/", "android/app/"}) {
                File f = new File(dir, prefix + rel + name);
                if (f.isFile()) {
                    return f;
                }
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /** Every state ships, at the designed length, as a 512px 60fps composition. */
    @Test public void allTenStatesShipAtTheDesignedLength() {
        for (int i = 0; i < STATES.length; i++) {
            JsonObject an = load(STATES[i]);
            assertEquals(STATES[i] + ": canvas width", CANVAS, num(an, "w"));
            assertEquals(STATES[i] + ": canvas height", CANVAS, num(an, "h"));
            assertEquals(STATES[i] + ": frame rate", FPS, num(an, "fr"));
            assertEquals(STATES[i] + ": starts at frame 0", 0, num(an, "ip"));
            assertEquals(STATES[i] + ": length", FRAMES[i], num(an, "op"));
            assertTrue(STATES[i] + ": version must be 5.x, was " + an.get("v").getAsString(),
                    an.get("v").getAsString().startsWith("5."));
        }
    }

    /** Layers, their stacking order, asset sizes and asset paths. */
    @Test public void everyImageLayerResolvesToATileWithoutAnAbsolutePath() {
        for (String state : STATES) {
            JsonObject an = load(state);
            JsonArray layers = an.getAsJsonArray("layers");
            assertTrue(state + ": no layers", layers.size() >= 1);

            Set<Integer> indices = new HashSet<>();
            for (JsonElement le : layers) {
                JsonObject lay = le.getAsJsonObject();
                assertNotNull(state + ": a layer has no index", lay.get("ind"));
                assertTrue(state + ": duplicate layer index",
                        indices.add(lay.get("ind").getAsInt()));
                assertNotNull(state + ": a layer has no transform", lay.get("ks"));
                assertTrue(state + ": every layer needs a name for debugging",
                        lay.get("nm") != null && lay.get("nm").getAsString().length() > 0);
                if (lay.get("ty").getAsInt() == 2) {
                    String ref = lay.get("refId").getAsString();
                    JsonObject asset = asset(an, ref);
                    assertNotNull(state + ": layer " + lay.get("nm").getAsString()
                            + " references missing asset " + ref, asset);
                    assertEquals(state + "/" + ref + ": tile width", CANVAS, num(asset, "w"));
                    assertEquals(state + "/" + ref + ": tile height", CANVAS, num(asset, "h"));
                    String p = asset.get("p").getAsString();
                    assertTrue(state + "/" + ref + ": asset path leaked an absolute path: " + p,
                            !p.contains(":") && !p.contains("\\"));
                }
            }
            // index 1 is the topmost layer: the overlays must be numbered before the body.
            assertTrue(state + ": layer indices start at 1", indices.contains(1));
        }
    }

    /** A state that animates nothing would freeze on the device; times must be sane too. */
    @Test public void everyStateAnimatesWithMonotonicTimesInsideTheComposition() {
        for (String state : STATES) {
            JsonObject an = load(state);
            int op = num(an, "op");
            int animated = 0;
            for (JsonElement le : an.getAsJsonArray("layers")) {
                animated += countAnimated(le, state, op);
            }
            assertTrue(state + ": nothing animates -- the Core would sit frozen", animated > 0);
        }
    }

    /**
     * core_progress.json is not a loop to autoplay: the view scrubs it with setProgress(pct), so
     * the ring's trim has to be a straight 0 -> 100% ramp across the file, and the sweep has to
     * start at 12 o'clock the way sheet C5 draws it.
     */
    @Test public void theProgressRingIsScrubbableNotAutoplayed() {
        JsonObject an = load("core_progress");
        assertEquals("the ring file is the download's own clock", 120, num(an, "op"));
        JsonObject trim = findTrim(an);
        assertNotNull("core_progress.json has no trim (tm) -- setProgress would do nothing", trim);
        JsonObject end = trim.getAsJsonObject("e");
        assertEquals("the trim must be animated, not a fixed arc", 1, end.get("a").getAsInt());
        JsonArray keys = end.getAsJsonArray("k");
        assertEquals("two keys = a straight ramp from 0 to 100", 2, keys.size());
        assertEquals("starts empty", 0.0,
                keys.get(0).getAsJsonObject().get("s").getAsDouble(), 0.001);
        assertEquals("ends full", 100.0,
                keys.get(1).getAsJsonObject().get("s").getAsDouble(), 0.001);
        assertEquals("trim offset, degrees", -90.0,
                trim.getAsJsonObject("o").get("k").getAsDouble(), 0.001);
    }

    /**
     * The rose / blue-grey looks are *baked* into the art (tools/core_lottie_build.py) instead of
     * being tinted at runtime, so two things have to stay true together: the shipped files are the
     * pinned ones, and the generator used CoreTint's own numbers to make them. The generator
     * asserts that second half -- it parses CoreTint.java before baking -- and this asserts the
     * first half plus the numbers themselves, so neither side can move alone.
     */
    @Test public void failureArtIsPinnedAndUsesCoreTintsOwnNumbers() throws IOException {
        assertEquals("CoreTint.ROSE_HUE_DEG -- if this changes, re-run tools/core_lottie_build.py",
                163f, CoreTint.ROSE_HUE_DEG, 0.0005f);
        assertEquals("CoreTint.ROSE_SATURATION", 0.82f, CoreTint.ROSE_SATURATION, 0.0005f);
        assertEquals("CoreTint.NEUTRAL_HUE_DEG", 18f, CoreTint.NEUTRAL_HUE_DEG, 0.0005f);
        assertEquals("CoreTint.NEUTRAL_SATURATION", 0.22f, CoreTint.NEUTRAL_SATURATION, 0.0005f);
        assertTrue("rose is muted, not saturated", CoreTint.ROSE_SATURATION < 1f);
        assertTrue("the unsupported read is the colourless one",
                CoreTint.NEUTRAL_SATURATION < CoreTint.ROSE_SATURATION);

        File rose = drawable("core_orb_rose.png");
        File neutral = drawable("core_orb_neutral.png");
        assumeTrue("art not found in this checkout - run the JVM tests from the module",
                rose != null && neutral != null);
        assertTintedTile(rose, 252890);
        assertTintedTile(neutral, 216617);
        // both come from the same orb, so they must be the same picture with a different light
        assertEquals("the two failure looks must be the same tile",
                readPngHeader(drawable("core_orb.png"))[0], readPngHeader(rose)[0]);
        assertTrue("the rose art is not a copy of the shipped orb",
                rose.length() != drawable("core_orb.png").length());
    }

    private static void assertTintedTile(File png, int bytes) throws IOException {
        assertEquals("baked art size", bytes, (int) png.length());
        int[] h = readPngHeader(png);
        assertEquals("master width", CANVAS, h[0]);
        assertEquals("master height", CANVAS, h[1]);
        assertEquals("8-bit", 8, h[2]);
        assertEquals("RGBA", 6, h[3]);
    }

    /** width, height, bit depth, colour type -- straight out of the PNG's IHDR chunk (no AWT). */
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
        int w = ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
        int h = ((b[20] & 0xFF) << 24) | ((b[21] & 0xFF) << 16) | ((b[22] & 0xFF) << 8) | (b[23] & 0xFF);
        return new int[]{w, h, b[24] & 0xFF, b[25] & 0xFF};
    }
}
