package fpv.platform;

import mindustry.core.Version;

/** Explicitly verified host versions; new revisions require native lifecycle regression checks. */
public final class HostCompatibility {
    public static final String SUPPORTED_VERSIONS = "160.5 and 160.7";

    private HostCompatibility() {}

    public static boolean supports(int build, int revision) {
        return build == 160 && (revision == 5 || revision == 7);
    }

    public static boolean supportsCurrent() { return supports(Version.build,Version.revision); }
    public static String supportedVersions() { return SUPPORTED_VERSIONS; }
    public static String currentVersion() { return Version.build+"."+Version.revision; }
}
