package app.builderx.ogfa.videoapichecker.Util;

public final class CallAutoCutConfig {
    public static final long AUTO_CUT_SECONDS = 60L;

    private CallAutoCutConfig() {
    }

    public static boolean isEnabled() {
        return AUTO_CUT_SECONDS > 0L;
    }
}
