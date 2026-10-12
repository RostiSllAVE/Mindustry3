package fpv.terrain;

import fpv.generated.TerrainConfig;
import java.util.Arrays;
import java.util.Random;

/** Standalone deterministic acceptance tests, without a running game or external test framework. */
public final class TerrainHeightFieldTest {
    private static int checks;
    private static final TerrainHeightField.Parameters defaults = new TerrainHeightField.Parameters(
        TerrainConfig.MINIMUM_HEIGHT_TILES, TerrainConfig.RISE_PER_LAYER, TerrainConfig.MAXIMUM_HEIGHT_TILES,
        (int)TerrainConfig.SMOOTHING_PASSES, TerrainConfig.HEIGHT_VARIATION, TerrainConfig.VARIATION_SCALE_TILES);

    public static void main(String[] args) {
        emptyAndOpenWorlds();
        isolatedAndNarrowWalls();
        exactEuclideanDistance();
        mountainRiseAndBound();
        continuousNaturalEdgesAndTriangleInterpolation();
        holesAndCliffs();
        smoothingAndOrganicVariation();
        immutableAndDeterministic();
        validation();
        System.out.println("TerrainHeightField: " + checks + " assertions passed (pure mask geometry; no native-map writes).");
    }

    private static void emptyAndOpenWorlds() {
        TerrainHeightField empty = TerrainHeightField.generate(0, 0, new boolean[0], defaults);
        close(empty.heightAt(0, 0), 0, 0, "empty world height");
        close(empty.maxHeight(), 0, 0, "empty peak");
        check(empty.naturalCount() == 0, "empty count");
        TerrainHeightField open = TerrainHeightField.generate(17, 11, new boolean[17 * 11], defaults);
        for (int y = -1; y <= 11; y++) for (int x = -1; x <= 17; x++) {
            close(open.heightAt(x, -y), 0, 0, "open/outside floor remains zero");
            close(open.vertexHeight(x, y), 0, 0, "open vertices remain zero");
        }
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            close(open.heightAt(invalid, 0), 0, 0, "nonfinite X safe");
            close(open.heightAt(0, invalid), 0, 0, "nonfinite Z safe");
        }
        close(open.heightAt(Double.MAX_VALUE, 0), 0, 0, "very large X outside");
        close(open.heightAt(0, -Double.MAX_VALUE), 0, 0, "very large Y outside");
    }

    private static void isolatedAndNarrowWalls() {
        TerrainHeightField one = TerrainHeightField.generate(1, 1, new boolean[]{true}, defaults);
        close(one.distanceAt(0, 0), 1, 0, "virtual open border bounds an all-wall single cell");
        close(one.heightAt(0, 0), defaults.minimumHeight(), 1e-7, "isolated natural wall keeps cliff height");
        for (int corner = 0; corner < 4; corner++) {
            close(one.tileCornerHeight(0, 0, corner), defaults.minimumHeight(), 1e-7, "isolated wall corner");
        }
        boolean[] narrow = new boolean[7 * 31];
        for (int y = 0; y < 31; y++) narrow[y * 7 + 3] = true;
        TerrainHeightField field = TerrainHeightField.generate(7, 31, narrow, defaults);
        check(field.naturalCount() == 31, "narrow mask count");
        for (int y = 0; y < 31; y++) {
            close(field.heightAt(3, -y), defaults.minimumHeight(), 1e-7, "one-cell ridge stays low");
            close(field.heightAt(2, -y), 0, 0, "narrow ridge does not raise open neighbor");
        }
    }

    private static void exactEuclideanDistance() {
        Random random = new Random(7182291);
        for (int trial = 0; trial < 35; trial++) {
            int width = 1 + random.nextInt(16), height = 1 + random.nextInt(16);
            boolean[] walls = new boolean[width * height];
            for (int i = 0; i < walls.length; i++) walls[i] = trial % 4 == 0 || random.nextDouble() < .8;
            TerrainHeightField field = TerrainHeightField.generate(width, height, walls, defaults);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                double expected = 0;
                if (walls[y * width + x]) {
                    expected = Math.min(Math.min(x + 1, width - x), Math.min(y + 1, height - y));
                    for (int oy = 0; oy < height; oy++) for (int ox = 0; ox < width; ox++) {
                        if (!walls[oy * width + ox]) expected = Math.min(expected, Math.hypot(x - ox, y - oy));
                    }
                }
                close(field.distanceAt(x, y), expected, 5e-7, "EDT matches brute-force nearest open tile");
            }
        }
    }

    private static void mountainRiseAndBound() {
        int size = 81;
        boolean[] walls = filled(size * size);
        TerrainHeightField.Parameters plain = new TerrainHeightField.Parameters(.85, 1.15, 30, 3, 0, 18);
        TerrainHeightField mountain = TerrainHeightField.generate(size, size, walls, plain);
        close(mountain.distanceAt(40, 40), 41, 0, "deep region has 41 erosion layers");
        check(mountain.heightAt(40, -40) > 22, "large region grows into mountain");
        check(mountain.heightAt(0, -40) < 3, "edge remains a low cliff");
        double previous = 0;
        for (int x = 0; x < 40; x++) {
            double h = mountain.heightAt(x, -40);
            check(h > previous, "deeper layers progressively rise before summit");
            previous = h;
        }
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            double height = mountain.heightAt(x, -y);
            check(Double.isFinite(height) && height >= .85 - 1e-6 && height <= 30, "every mountain sample bounded");
        }
        check(mountain.maxHeight() <= 30 && mountain.maxHeight() > 22, "reported actual peak is bounded");
    }

    private static void continuousNaturalEdgesAndTriangleInterpolation() {
        TerrainHeightField field = TerrainHeightField.generate(31, 27, filled(31 * 27), defaults);
        for (int y = 1; y < 25; y += 3) for (int x = 1; x < 29; x += 3) {
            close(field.tileCornerHeight(x, y, 1), field.tileCornerHeight(x + 1, y, 0), 0, "adjacent SW-SE shared corner");
            close(field.tileCornerHeight(x, y, 2), field.tileCornerHeight(x + 1, y, 3), 0, "adjacent NE-NW shared corner");
            close(field.heightAt(x + .5 - 1e-7, -(y + .13)), field.heightAt(x + .5 + 1e-7, -(y + .13)),
                2e-6, "continuous height over shared natural edge");
            double h0 = field.tileCornerHeight(x, y, 0), h1 = field.tileCornerHeight(x, y, 1);
            double h2 = field.tileCornerHeight(x, y, 2), h3 = field.tileCornerHeight(x, y, 3);
            close(field.heightAt(x - .5 + .8, -(y - .5 + .2)), h0 * .2 + h1 * .6 + h2 * .2,
                1e-12, "first triangle barycentric height equals rendered surface");
            close(field.heightAt(x - .5 + .2, -(y - .5 + .8)), h0 * .2 + h2 * .2 + h3 * .6,
                1e-12, "second triangle barycentric height equals rendered surface");
            close(field.heightAt(x, -y), (h0 + h2) * .5, 1e-12, "diagonal interpolation exactly matches both triangles");
            close(field.heightAt(x + .4, -(y + .4 - 1e-8)), field.heightAt(x + .4, -(y + .4 + 1e-8)),
                1e-6, "continuous over triangle diagonal");
        }
    }

    private static void holesAndCliffs() {
        boolean[] walls = filled(17 * 17);
        walls[8 * 17 + 8] = false;
        TerrainHeightField field = TerrainHeightField.generate(17, 17, walls, defaults);
        close(field.distanceAt(9, 8), 1, 0, "internal hole contributes distance boundary");
        close(field.distanceAt(10, 10), Math.sqrt(8), 2e-7, "diagonal distance to internal hole is Euclidean");
        for (double u : new double[]{-.49, -.25, 0, .25, .49}) for (double v : new double[]{-.49, 0, .49}) {
            close(field.heightAt(8 + u, -(8 + v)), 0, 0, "entire open hole stays flat, not just its center");
        }
        for (int corner = 0; corner < 4; corner++) close(field.tileCornerHeight(8, 8, corner), 0, 0, "open cell corners zero");
        check(field.vertexHeight(8, 8) > 0, "raw shared vertex may border open cell; renderer skips it for open cells");
        close(field.heightAt(7.5, -8), 0, 0, "half-open X belongs to open cell at cliff boundary");
        check(field.heightAt(7.5 - 1e-6, -8) > .84, "wall side of cliff remains elevated");
        close(field.heightAt(-.500001, 0), 0, 0, "outside map has no invented collision mountain");
        check(field.heightAt(-.5, 0) > 0, "left map edge belongs to first natural cell");
        close(field.heightAt(16.5, 0), 0, 0, "right map edge belongs outside");
    }

    private static void smoothingAndOrganicVariation() {
        int size = 53;
        boolean[] walls = filled(size * size);
        TerrainHeightField raw = TerrainHeightField.generate(size, size, walls,
            new TerrainHeightField.Parameters(.85, 1.15, 30, 0, .23, 18));
        TerrainHeightField smooth = TerrainHeightField.generate(size, size, walls, defaults);
        check(curvature(smooth) < curvature(raw), "smoothing reduces interior second-difference energy");
        TerrainHeightField plain = TerrainHeightField.generate(size, size, walls,
            new TerrainHeightField.Parameters(.85, 1.15, 30, 3, 0, 18));
        close(plain.heightAt(12, -26), plain.heightAt(40, -26), 1e-6, "unmodulated rectangle remains symmetric");
        check(Math.abs(smooth.heightAt(12, -26) - smooth.heightAt(40, -26)) > .03,
            "broad modulation breaks identical straight contour shapes");
        double maxSlope = 0;
        for (int y = 0; y < size - 1; y++) for (int x = 0; x < size - 1; x++) {
            maxSlope = Math.max(maxSlope, Math.abs(smooth.heightAt(x, -y) - smooth.heightAt(x + 1, -y)));
        }
        check(maxSlope < 1.5, "interior rises smoothly instead of stacked block steps");
    }

    private static double curvature(TerrainHeightField field) {
        double sum = 0;
        for (int y = 1; y < field.height() - 1; y++) for (int x = 1; x < field.width() - 1; x++) {
            double dx = field.centerHeight(x - 1, y) + field.centerHeight(x + 1, y) - 2.0 * field.centerHeight(x, y);
            double dy = field.centerHeight(x, y - 1) + field.centerHeight(x, y + 1) - 2.0 * field.centerHeight(x, y);
            sum += dx * dx + dy * dy;
        }
        return sum;
    }

    private static void immutableAndDeterministic() {
        boolean[] walls = filled(25 * 29), original = walls.clone();
        walls[30] = original[30] = false;
        TerrainHeightField a = TerrainHeightField.generate(25, 29, walls, defaults);
        check(Arrays.equals(walls, original), "generation does not modify source mask");
        TerrainHeightField b = TerrainHeightField.generate(25, 29, walls, defaults);
        check(a.maskFingerprint() == b.maskFingerprint(), "same source fingerprint");
        for (int y = 0; y <= 29; y++) for (int x = 0; x <= 25; x++) {
            check(Float.floatToIntBits(a.vertexHeight(x, y)) == Float.floatToIntBits(b.vertexHeight(x, y)),
                "same input generates identical vertex bits");
        }
        Arrays.fill(walls, false);
        check(a.naturalCount() == original.length - 1 && a.isNatural(12, 12), "field owns immutable mask snapshot");
        check(a.heightAt(12, -12) > 5, "caller mutating mask cannot alter installed geometry");
        TerrainHeightField edited = TerrainHeightField.generate(25, 29, walls, defaults);
        check(a.maskFingerprint() != edited.maskFingerprint(), "mask edits change fingerprint");
        close(edited.maxHeight(), 0, 0, "fresh rebuild removes erased mountains");
    }

    private static void validation() {
        rejects(() -> TerrainHeightField.generate(-1, 2, new boolean[0], defaults), "negative dimensions");
        rejects(() -> TerrainHeightField.generate(2, 2, new boolean[3], defaults), "wrong mask size");
        rejects(() -> TerrainHeightField.generate(2, 2, null, defaults), "null mask");
        rejects(() -> TerrainHeightField.generate(2, 2, new boolean[4], null), "null parameters");
        rejects(() -> TerrainHeightField.generate(Integer.MAX_VALUE, 2, new boolean[0], defaults), "overflow dimensions");
        rejects(() -> new TerrainHeightField.Parameters(0, 1, 30, 3, .2, 18), "zero minimum");
        rejects(() -> new TerrainHeightField.Parameters(1, 0, 30, 3, .2, 18), "zero rise");
        rejects(() -> new TerrainHeightField.Parameters(1, 1, 1, 3, .2, 18), "cap below minimum");
        rejects(() -> new TerrainHeightField.Parameters(1, 1, 30, -1, .2, 18), "negative smoothing");
        rejects(() -> new TerrainHeightField.Parameters(1, 1, 30, 33, .2, 18), "unbounded smoothing");
        rejects(() -> new TerrainHeightField.Parameters(1, 1, 30, 3, -.1, 18), "negative variation");
        rejects(() -> new TerrainHeightField.Parameters(1, 1, 30, 3, Double.NaN, 18), "nonfinite variation");
        rejects(() -> new TerrainHeightField.Parameters(1, 1, 30, 3, .2, .1), "tiny variation scale");
        TerrainHeightField one = TerrainHeightField.generate(1, 1, new boolean[]{true}, defaults);
        rejects(() -> one.tileCornerHeight(0, 0, 4), "invalid corner index");
    }

    private static boolean[] filled(int size) { boolean[] values = new boolean[size]; Arrays.fill(values, true); return values; }
    private static void rejects(Runnable action, String message) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message + " should reject invalid input");
    }
    private static void close(double actual, double expected, double tolerance, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
            message + ": actual=" + actual + ", expected=" + expected + ", tolerance=" + tolerance);
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
}
