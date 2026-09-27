package com.omnidownloader.app.downicore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@link CoreLottie} decides which authored animation each state plays, so it is checked against the
 * shipped JSON instead of being trusted. This is the wiring half of the contract whose file half is
 * {@link CoreLottieSpecTest}:
 *
 *   · the table is the plan's mapping (§3), written out a second time here on purpose so moving a
 *     state's file in one place and not the other fails the build;
 *   · every file the generator ships is either wired or on the documented "not wired, and why"
 *     list, so an authored animation can never be silently ignored;
 *   · the two behavioural flags are exactly two: progress is scrubbed, READY loops, nothing else;
 *   · {@link CoreLottie#usesPausedTile} agrees with the tile a file's own image layers use, because
 *     the two sheet tiles pad the canvas differently and the wrong ratio shrinks the disc.
 */
public class CoreLottieWiringTest {

    /** V3.3_PLAN.md section 3, as a table of what plays for what. */
    private static final Map<String, String> WIRED = new HashMap<>();
    static {
        WIRED.put(CoreStates.WAKE, "core_wake");
        WIRED.put(CoreStates.DETECTED, "core_idle_ready");
        WIRED.put(CoreStates.PRESSED, "core_press");
        WIRED.put(CoreStates.PROGRESS, "core_progress");
        WIRED.put(CoreStates.PAUSED, "core_pause");
        WIRED.put(CoreStates.COMPLETE, "core_complete");
    }

    private static final String FAILURE = "core_failure";
    private static final String UNSUPPORTED = "core_unsupported";

    /** The states with no authored file: movement, cross-fades and the resting look. */
    private static final String[] STATIC_ONLY = {
            CoreStates.IDLE, CoreStates.RESOLVING, CoreStates.DRAGGING, CoreStates.SNAPPED,
            CoreStates.RESUMING, CoreStates.COMPLETING};

    /** Shipped and deliberately not wired — an owner decision, not an oversight. */
    private static final String[] UNWIRED = {CoreLottie.DORMANT, CoreLottie.RETRY};

    @Test public void everyStatePlaysTheFileTheDesignSaysItDoes() {
        for (String state : CoreStates.ALL) {
            if (CoreStates.FAILED.equals(state)) {
                continue;                       // FAILED picks between two files; asserted below
            }
            String expected = WIRED.containsKey(state) ? WIRED.get(state) : null;
            assertEquals(state + " plays the wrong file", expected,
                    CoreLottie.assetFor(state, false));
        }
        assertEquals("FAILED is the rose read", FAILURE,
                CoreLottie.assetFor(CoreStates.FAILED, false));
        assertEquals("...and the neutral one when the answer is 'not this kind of thing'",
                UNSUPPORTED, CoreLottie.assetFor(CoreStates.FAILED, true));
    }

    @Test public void theStatesWithNoFileKeepTheArtTheDeviceGateVerified() {
        for (String state : STATIC_ONLY) {
            assertNull(state + " must stay on the static art", CoreLottie.assetFor(state, false));
            assertNull(state + " must stay on the static art", CoreLottie.assetFor(state, true));
        }
        assertNull("an unknown state is never wired to something arbitrary",
                CoreLottie.assetFor("", false));
        assertNull(CoreLottie.assetFor(null, false));
    }

    @Test public void exactlyTwoFilesAreSpecialAndTheyAreScrubAndLoop() {
        assertTrue("progress is scrubbed by the real fraction, never played",
                CoreLottie.isScrubbed(CoreLottie.PROGRESS_FILE));
        assertTrue("READY is the loop", CoreLottie.loops(CoreLottie.READY_FILE));
        for (String file : CoreLottie.SHIPPED) {
            if (!CoreLottie.PROGRESS_FILE.equals(file)) {
                assertFalse(file + " must not be scrubbed", CoreLottie.isScrubbed(file));
            }
            if (!CoreLottie.READY_FILE.equals(file)) {
                assertFalse(file + " must not loop", CoreLottie.loops(file));
            }
        }
    }

    @Test public void everyShippedFileIsOnDisk() {
        for (String file : CoreLottie.SHIPPED) {
            File f = json(file);
            assumeTrue("assets/core not found in this checkout - run the JVM tests from the module",
                    f != null);
            assertEquals(file + ".json is missing from assets/core", file + ".json", f.getName());
        }
    }

    @Test public void everyShippedFileIsWiredOrDocumentedAsNot() {
        Set<String> unwired = new HashSet<>();
        for (String file : UNWIRED) {
            unwired.add(file);
        }
        assertEquals("the unwired list is a decision: keep it short and explained",
                2, UNWIRED.length);
        for (String file : CoreLottie.SHIPPED) {
            if (unwired.contains(file)) {
                assertFalse(file + " is on the 'not wired' list but the table plays it",
                        CoreLottie.isWired(file));
            } else {
                assertTrue(file + " ships but no state plays it: wire it or document why not",
                        CoreLottie.isWired(file));
            }
        }
    }

    /**
     * The disc lands on 2r by dividing the composition's side by its object/tile ratio, so a comp
     * built on the two-bar tile has to be flagged or its disc comes out small. Checked against the
     * tiles the file itself references, not against a second copy of the flag.
     */
    @Test public void thePausedTileFlagMatchesTheArtEachFileUses() {
        for (String file : CoreLottie.SHIPPED) {
            JsonObject an = load(file);
            if (an == null) {
                continue;                              // outside the module: the other test skipped
            }
            Set<String> tiles = imageAssetIds(an);
            assertTrue(file + " references no image tile at all", tiles.size() > 0);
            if (CoreLottie.usesPausedTile(file)) {
                assertTrue(file + " is flagged as a two-bar comp but uses " + tiles,
                        tiles.contains("core_orb_paused.png"));
                assertFalse(file + " mixes the two sheet tiles", tiles.contains("core_orb.png"));
            } else {
                assertTrue(file + " plays the main orb but does not reference it: " + tiles,
                        tiles.contains("core_orb.png"));
            }
        }
    }

    /** Every file asks only for tiles the view can supply (the image delegate's full set). */
    @Test public void everyFileOnlyAsksForTilesTheViewHas() {
        Set<String> known = new HashSet<>();
        known.add("core_orb.png");
        known.add("core_orb_paused.png");
        known.add("core_orb_rose.png");
        known.add("core_orb_neutral.png");
        for (String file : CoreLottie.SHIPPED) {
            JsonObject an = load(file);
            if (an == null) {
                continue;
            }
            for (String tile : imageAssetIds(an)) {
                assertTrue(file + " asks for " + tile + ", which CoreHost cannot supply: the whole"
                        + " stage would be refused and the static art would draw instead",
                        known.contains(tile));
            }
        }
    }

    // --- helpers -------------------------------------------------------------

    /** The image asset ids a file declares (image assets carry a path; figure assets do not). */
    private static Set<String> imageAssetIds(JsonObject an) {
        Set<String> out = new HashSet<>();
        JsonElement assets = an.get("assets");
        if (assets == null || !assets.isJsonArray()) {
            return out;
        }
        for (JsonElement a : assets.getAsJsonArray()) {
            JsonObject asset = a.getAsJsonObject();
            if (asset.has("p") && asset.has("id")) {
                out.add(asset.get("id").getAsString());
            }
        }
        return out;
    }

    private static JsonObject load(String file) {
        File f = json(file);
        assumeTrue("assets/core not found in this checkout - run the JVM tests from the module",
                f != null);
        try (FileReader r = new FileReader(f)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError(file + ".json is not valid JSON: " + e, e);
        }
    }

    /** The same lookup CoreLottieSpecTest uses (package-private there: one search, two tests). */
    private static File json(String file) {
        return CoreLottieSpecTest.search("src/main/assets/core/", file + ".json");
    }
}
