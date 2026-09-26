package com.omnidownloader.app.fetcher;

/**
 * The named resolution routes (Wave 1). One spelling everywhere: the profile's route
 * order, the observer's step events, the strategy ledger and the logs all speak these
 * names, so a route can never be "sheet_tree" in one file and "sheettree" in another.
 *
 * The routes are the mechanisms measured on device (see DEVICE_TEST.md §5 route
 * attribution): the ledger lane resolves INSTANTLY when the platform's tree leaks the
 * media URL (Instagram only, historically); the sheet-tree lane reads the URL from the
 * open share surface before clicking anything; the copy-link chain is the proven
 * workhorse that drives the platform's own "Copy link" and reads the clipboard.
 */
public final class Route {

    public static final String LEDGER = "ledger";
    public static final String SHEET_TREE = "sheet_tree";
    public static final String COPY_LINK = "copy_link";

    private Route() {}

    /** True when the name is one of the known routes. */
    public static boolean isKnown(String route) {
        return LEDGER.equals(route) || SHEET_TREE.equals(route) || COPY_LINK.equals(route);
    }
}
