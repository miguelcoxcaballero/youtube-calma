package es.calma.youtube.nativeapp;

/** Removes recommendation rows before native Litho layout, without blank slots. */
public final class CalmaWatch {
    private static volatile boolean watching;

    public static void playerType(Enum<?> state) {
        if (state == null) return;
        String name=state.name();
        watching=name.equals("WATCH_WHILE_MAXIMIZED") || name.equals("WATCH_WHILE_FULLSCREEN")
            || name.equals("WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN");
    }

    public static boolean filter(String identifier, StringBuilder path) {
        if (identifier == null) return false;
        // Specific recommendation components are safe to remove even while the
        // player is transitioning. Metadata, description and comments stay native.
        if (identifier.startsWith("related_video_with_context")
                || identifier.startsWith("fullscreen_related_videos")) return true;
        boolean watchPath=path != null && (path.indexOf("watch_next") >= 0 || path.indexOf("related_video") >= 0);
        if (!watching && !watchPath) return false;
        if (path != null && (path.indexOf("comment") >= 0 || path.indexOf("video_description") >= 0
                || path.indexOf("search_video") >= 0 || path.indexOf("FEsubscriptions") >= 0)) return false;
        return identifier.startsWith("compact_video.")
            || identifier.startsWith("video_with_context.")
            || identifier.startsWith("video_lockup_with_attachment.")
            || identifier.startsWith("video_card.")
            || identifier.startsWith("related_chip_cloud.");
    }
}
