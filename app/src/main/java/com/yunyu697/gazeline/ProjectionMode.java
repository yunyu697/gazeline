package com.yunyu697.gazeline;

import java.util.Locale;

/**
 * How a frame maps onto the eyes: the projection (flat screen, equirect
 * 360°/180°, fisheye) plus the stereo packing (mono, side-by-side,
 * top-bottom). Everything the shaders need is derived from these two numbers,
 * so the player only passes the mode down.
 *
 * <p>Detection is name first — VR exports almost always carry 360/180/fisheye
 * and sbs/tb in the filename — and the frame shape second: 2:1 is a matched
 * 360 sphere, 4:1 two spheres, 1:1 a half sphere. A normal 16:9 file falls
 * back to a flat screen instead of being wrapped onto a sphere.
 */
final class ProjectionMode {

    static final int FLAT = 0;
    static final int EQ360 = 1;
    static final int EQ180 = 2;
    static final int FISH180 = 3;
    static final int FISH200 = 4;
    static final int PROJ_COUNT = 5;

    static final int MONO = 0;
    static final int SBS = 1;
    static final int TB = 2;
    static final int PACK_COUNT = 3;

    static final String[] PROJ_LABELS = {"平面", "360°", "180°", "鱼眼180°", "鱼眼200°"};
    static final String[] PACK_LABELS = {"单眼", "左右", "上下"};
    private static final String[] PROJ_KEYS = {"flat", "360", "180", "fish180", "fish200"};

    static final ProjectionMode FLAT_MONO = new ProjectionMode(FLAT, MONO);

    final int proj;
    final int pack;

    ProjectionMode(int proj, int pack) {
        this.proj = Math.max(0, Math.min(PROJ_COUNT - 1, proj));
        this.pack = Math.max(0, Math.min(PACK_COUNT - 1, pack));
    }

    static ProjectionMode of(int proj, int pack) {
        return new ProjectionMode(proj, pack);
    }

    boolean isFlat() {
        return proj == FLAT;
    }

    boolean isFisheye() {
        return proj == FISH180 || proj == FISH200;
    }

    /** Half the horizontal coverage of one eye's image, in degrees. */
    float halfLonDeg() {
        return proj == EQ180 ? 90f : 180f;
    }

    /** Full diagonal field of the fisheye circle. */
    float fisheyeFovDeg() {
        return proj == FISH200 ? 200f : 180f;
    }

    String projLabel() {
        return PROJ_LABELS[proj];
    }

    String packLabel() {
        return PACK_LABELS[pack];
    }

    String label() {
        return projLabel() + " · " + packLabel();
    }

    String key() {
        if (proj == FLAT && pack == MONO) {
            return "flat";
        }
        String base = PROJ_KEYS[proj];
        if (pack == SBS) {
            return base + "-sbs";
        }
        if (pack == TB) {
            return base + "-tb";
        }
        return base;
    }

    /** Rebuild from {@link #key()} (and the older "360"/"sbs" history values). */
    static ProjectionMode parse(String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.trim().toLowerCase(Locale.US).replace(' ', '-');
        if (k.length() == 0 || "auto".equals(k)) {
            return null;
        }
        int pack = MONO;
        if (k.contains("sbs") || k.contains("lr") || k.contains("左右")) {
            pack = SBS;
        } else if (k.contains("tb") || k.contains("ou") || k.contains("上下")) {
            pack = TB;
        }
        if (k.contains("fish") || k.contains("鱼眼")) {
            return new ProjectionMode(k.contains("200") ? FISH200 : FISH180, pack);
        }
        if (k.contains("180")) {
            return new ProjectionMode(EQ180, pack);
        }
        if (k.contains("360") || k.contains("equirect") || k.contains("全景")) {
            return new ProjectionMode(EQ360, pack);
        }
        if (k.contains("flat") || k.contains("平面") || k.contains("2d")) {
            return new ProjectionMode(FLAT, pack);
        }
        return null;
    }

    /** Human label for a stored setting; null/"auto" reads as automatic. */
    static String labelOf(String raw) {
        ProjectionMode p = parse(raw);
        return p == null ? "自动" : p.projLabel();
    }

    /**
     * Next value of the 自动 → 平面 → 360° → 180° → 鱼眼180° → 鱼眼200° cycle used
     * by the library toggle and the in-VR globe button.
     */
    static String cycleKey(String raw) {
        ProjectionMode cur = parse(raw);
        int pack = cur == null ? MONO : cur.pack;
        int next = (cur == null ? -1 : cur.proj) + 1;
        if (next >= PROJ_COUNT) {
            return "auto";
        }
        return new ProjectionMode(next, pack).key();
    }

    /** True when the filename itself says how the two eyes are laid out. */
    static boolean nameHasPack(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        return n.contains("sbs") || n.contains("hsbs") || n.contains("_lr")
                || n.contains("-lr") || n.contains("左右") || n.contains("_tb")
                || n.contains("-tb") || n.contains("_ou") || n.contains("-ou")
                || n.contains("上下") || n.contains("over-under") || n.contains("mono");
    }

    /**
     * Apply a packing proven by the picture itself. The frame shape cannot tell
     * a 2:1 mono sphere from a 2:1 pair of 1:1 eyes (VR180 SBS), so once the
     * pixels show two eyes the per-eye shape decides the family: 2:1 per eye
     * spans 360°, 1:1 per eye spans 180°.
     */
    static ProjectionMode withPack(ProjectionMode cur, int pack, float ar) {
        int proj = cur.proj;
        if (proj == EQ360) {
            if (pack == SBS && ar >= 1.7f && ar <= 2.3f) {
                proj = EQ180;
            } else if (pack == TB && ar >= 0.85f && ar <= 1.2f) {
                proj = EQ180;
            }
        } else if (proj == EQ180 && pack == SBS && ar >= 3.0f) {
            proj = EQ360;
        }
        return new ProjectionMode(proj, pack);
    }

    /**
     * Best guess for one file. {@code width}/{@code height} may be 0 when the
     * decoder has not reported the track yet; the name hint alone is then used.
     * Never returns null: an ordinary file becomes a flat screen.
     */
    static ProjectionMode detect(String name, int width, int height,
                                 ProjectionMode fallback) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        boolean sbs = n.contains("sbs") || n.contains("hsbs") || n.contains("_lr")
                || n.contains("-lr") || n.contains("左右");
        boolean tb = n.contains("_tb") || n.contains("-tb") || n.contains("_ou")
                || n.contains("-ou") || n.contains("上下") || n.contains("over-under");
        int pack = sbs ? SBS : (tb ? TB : -1);
        float ar = (width > 0 && height > 0) ? (width * 1f) / height : 0f;

        boolean fish = n.contains("fisheye") || n.contains("鱼眼") || n.contains("mkx")
                || n.contains("dual-fish") || n.contains("vrca");
        if (fish) {
            int p = pack >= 0 ? pack : (ar >= 1.6f ? SBS : MONO);
            return new ProjectionMode(n.contains("220") || n.contains("200")
                    ? FISH200 : FISH180, p);
        }
        boolean is180 = n.contains("180") && !n.contains("1800");
        boolean is360 = n.contains("360") || n.contains("equirect") || n.contains("全景");
        if (is180) {
            int p = pack >= 0 ? pack : (ar >= 1.6f ? SBS : MONO);
            return new ProjectionMode(EQ180, p);
        }
        if (is360) {
            // The name promises a sphere, not a packing: a 4:1 frame is one
            // sphere per eye, 2:1 is a single sphere. Treating 4:1 as mono
            // wrapped both eyes around one sphere and put the seam dead ahead.
            int p = pack;
            if (p < 0) {
                p = ar >= 3.0f ? SBS : (ar > 0f && ar <= 1.35f ? TB : MONO);
            }
            return new ProjectionMode(EQ360, p);
        }
        // No name hint: the frame shape decides, and a shape that fits none of
        // them falls back to the user's manual choice (auto means flat).
        if (ar >= 3.6f) {
            return new ProjectionMode(EQ360, SBS);
        }
        if (ar >= 1.85f && ar <= 2.15f) {
            return new ProjectionMode(EQ360, MONO);
        }
        if (ar > 0f && ar <= 1.05f) {
            return new ProjectionMode(EQ180, MONO);
        }
        ProjectionMode d = fallback == null ? FLAT_MONO : fallback;
        return new ProjectionMode(d.proj, pack >= 0 ? pack : d.pack);
    }
}
