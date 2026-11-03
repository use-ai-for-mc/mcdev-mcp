package dev.mcdevmcp.support;

public final class RequiredRuntime {
    public static final int FEATURE = 28;

    private RequiredRuntime() {
    }

    public static String previewRequirement() {
        return "Java " + FEATURE + " with --enable-preview is required";
    }
}
