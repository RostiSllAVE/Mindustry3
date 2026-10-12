package fpv.terrain;

/**
 * Immutable, game-independent height field made from natural wall membership.
 * Native XY remains the only horizontal topology. Heights are presentation data in tiles.
 */
public final class TerrainHeightField {
    public record Parameters(double minimumHeight, double risePerLayer, double maximumHeight,
                             int smoothingPasses, double variation, double variationScale) {
        public Parameters {
            if (!Double.isFinite(minimumHeight) || minimumHeight <= 0 ||
                !Double.isFinite(risePerLayer) || risePerLayer <= 0 ||
                !Double.isFinite(maximumHeight) || maximumHeight <= minimumHeight ||
                smoothingPasses < 0 || smoothingPasses > 32 ||
                !Double.isFinite(variation) || variation < 0 || variation > .75 ||
                !Double.isFinite(variationScale) || variationScale < 1) {
                throw new IllegalArgumentException("Invalid natural terrain parameters");
            }
        }
    }

    private final int width, height, naturalCount;
    private final boolean[] natural;
    private final float[] distance, centers, vertices;
    private final float maximumHeight;
    private final long maskFingerprint;

    private TerrainHeightField(int width, int height, boolean[] source, Parameters parameters) {
        this.width = width;
        this.height = height;
        natural = source.clone();
        int count = 0;
        long hash = mixFingerprint(mixFingerprint(0xcbf29ce484222325L, width), height);
        for (boolean wall : natural) {
            if (wall) count++;
            hash = mixFingerprint(hash, wall ? 1 : 0);
        }
        naturalCount = count;
        maskFingerprint = hash;
        distance = distances(width, height, natural);
        float[] heights = new float[source.length];
        double span = parameters.maximumHeight - parameters.minimumHeight;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = index(x, y);
                if (!natural[i]) continue;
                double depth = Math.max(0.0, distance[i] - 1.0);
                // Broad modulation bends otherwise identical distance contours without moving native cells.
                double noise = .64 * noise(x / parameters.variationScale, y / parameters.variationScale) +
                    .36 * noise((x + 119.7) / (parameters.variationScale * 2.7),
                                (y - 71.3) / (parameters.variationScale * 2.7));
                double rise = parameters.risePerLayer * depth * (1.0 + parameters.variation * noise);
                heights[i] = (float)(parameters.minimumHeight + span * -Math.expm1(-rise / span));
            }
        }
        float[] scratch = new float[heights.length];
        for (int pass = 0; pass < parameters.smoothingPasses; pass++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = index(x, y);
                    if (!natural[i]) continue;
                    double sum = heights[i] * 4.0, weight = 4.0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if ((dx == 0 && dy == 0) || !isNatural(x + dx, y + dy)) continue;
                            double w = dx == 0 || dy == 0 ? 2.0 : 1.0;
                            sum += heights[index(x + dx, y + dy)] * w;
                            weight += w;
                        }
                    }
                    scratch[i] = (float)(sum / weight);
                }
            }
            float[] swap = heights;
            heights = scratch;
            scratch = swap;
        }
        centers = heights;
        vertices = new float[(width + 1) * (height + 1)];
        float peak = 0;
        for (int y = 0; y <= height; y++) {
            for (int x = 0; x <= width; x++) {
                double sum = 0;
                int contributors = 0;
                for (int dy = -1; dy <= 0; dy++) {
                    for (int dx = -1; dx <= 0; dx++) {
                        if (!isNatural(x + dx, y + dy)) continue;
                        sum += centers[index(x + dx, y + dy)];
                        contributors++;
                    }
                }
                float value = contributors == 0 ? 0 : (float)(sum / contributors);
                vertices[y * (width + 1) + x] = value;
                peak = Math.max(peak, value);
            }
        }
        maximumHeight = peak;
    }

    public static TerrainHeightField generate(int width, int height, boolean[] natural, Parameters parameters) {
        if (width < 0 || height < 0 || (long)width * height > Integer.MAX_VALUE ||
            ((long)width + 1) * ((long)height + 1) > Integer.MAX_VALUE || natural == null ||
            natural.length != (long)width * height || parameters == null) {
            throw new IllegalArgumentException("Invalid terrain dimensions or source mask");
        }
        return new TerrainHeightField(width, height, natural, parameters);
    }

    public int width() { return width; }
    public int height() { return height; }
    public int naturalCount() { return naturalCount; }
    public float maxHeight() { return maximumHeight; }
    /** Fingerprint of dimensions and the cloned natural mask, not mutable simulation state. */
    public long maskFingerprint() { return maskFingerprint; }

    public boolean isNatural(int tileX, int tileY) {
        return tileX >= 0 && tileY >= 0 && tileX < width && tileY < height && natural[index(tileX, tileY)];
    }

    /** Exact Euclidean distance in tiles, zero in open cells and outside the map. */
    public float distanceAt(int tileX, int tileY) {
        return isNatural(tileX, tileY) ? distance[index(tileX, tileY)] : 0;
    }

    /** Smoothed cell sample before corner averaging; useful for diagnostics. */
    public float centerHeight(int tileX, int tileY) {
        return isNatural(tileX, tileY) ? centers[index(tileX, tileY)] : 0;
    }

    /**
     * Shared raw corner in integer native XY corner coordinates. Draw this only for natural tiles;
     * an incident open tile stays flat and the resulting exterior edge is an intentional cliff.
     */
    public float vertexHeight(int cornerX, int cornerY) {
        if (cornerX < 0 || cornerY < 0 || cornerX > width || cornerY > height) return 0;
        return vertices[cornerY * (width + 1) + cornerX];
    }

    /** Corners 0=SW, 1=SE, 2=NE, 3=NW in native game XY; non-wall cells always return zero. */
    public float tileCornerHeight(int tileX, int tileY, int corner) {
        if (corner < 0 || corner > 3) throw new IllegalArgumentException("Corner must be 0..3");
        if (!isNatural(tileX, tileY)) return 0;
        return vertexHeight(tileX + (corner == 1 || corner == 2 ? 1 : 0),
                            tileY + (corner >= 2 ? 1 : 0));
    }

    /**
     * Render X/Z query, with native Y = -Z. Matches triangles (0,1,2) and (0,2,3) exactly.
     * Cell ownership is half-open: floor(X+.5), floor(-Z+.5). Vertical natural/open cliffs
     * are the only discontinuities; collision consumers must sweep between samples.
     */
    public double heightAt(double renderX, double renderZ) {
        if (!Double.isFinite(renderX) || !Double.isFinite(renderZ)) return 0;
        double gridX = renderX + .5, gridY = -renderZ + .5;
        if (gridX < 0 || gridY < 0 || gridX >= width || gridY >= height) return 0;
        int x = (int)Math.floor(gridX), y = (int)Math.floor(gridY);
        if (!isNatural(x, y)) return 0;
        double u = gridX - x, v = gridY - y;
        double h0 = vertexHeight(x, y), h2 = vertexHeight(x + 1, y + 1);
        if (v <= u) {
            return h0 * (1.0 - u) + vertexHeight(x + 1, y) * (u - v) + h2 * v;
        }
        return h0 * (1.0 - v) + h2 * u + vertexHeight(x, y + 1) * (v - u);
    }

    private int index(int x, int y) { return y * width + x; }
    private static long mixFingerprint(long hash, int value) { return (hash ^ value) * 0x100000001b3L; }

    /** O(width*height) separable squared-distance transform, including an open virtual border. */
    private static float[] distances(int width, int height, boolean[] natural) {
        float[] result = new float[natural.length];
        if (width == 0 || height == 0) return result;
        for (int y = 0; y < height; y++) {
            int nearest = -1;
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                if (!natural[i]) nearest = x;
                double delta = x - nearest;
                result[i] = (float)(delta * delta);
            }
            nearest = width;
            for (int x = width - 1; x >= 0; x--) {
                int i = y * width + x;
                if (!natural[i]) nearest = x;
                double delta = nearest - x;
                result[i] = (float)Math.min(result[i], delta * delta);
            }
        }
        // The parabola envelope computes min_q(f[q] + (y-q)^2). Border rows cost zero.
        double[] f = new double[height + 2], d = new double[height + 2], cuts = new double[height + 3];
        int[] sites = new int[height + 2];
        for (int x = 0; x < width; x++) {
            f[0] = f[height + 1] = 0;
            for (int y = 0; y < height; y++) f[y + 1] = result[y * width + x];
            transformLine(f, d, sites, cuts);
            for (int y = 0; y < height; y++) result[y * width + x] = (float)Math.sqrt(d[y + 1]);
        }
        return result;
    }

    private static void transformLine(double[] f, double[] d, int[] sites, double[] cuts) {
        int k = 0;
        sites[0] = 0;
        cuts[0] = Double.NEGATIVE_INFINITY;
        cuts[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < f.length; q++) {
            double crossing;
            do {
                int p = sites[k];
                crossing = ((f[q] + (double)q * q) - (f[p] + (double)p * p)) / (2.0 * (q - p));
                if (crossing <= cuts[k]) k--;
                else break;
            } while (k >= 0);
            k++;
            sites[k] = q;
            cuts[k] = crossing;
            cuts[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < f.length; q++) {
            while (cuts[k + 1] < q) k++;
            double delta = q - sites[k];
            d[q] = delta * delta + f[sites[k]];
        }
    }

    /** Smooth deterministic value noise independent of the game's RNG and save seed. */
    private static double noise(double x, double y) {
        int ix = (int)Math.floor(x), iy = (int)Math.floor(y);
        double u = x - ix, v = y - iy;
        u = u * u * (3.0 - 2.0 * u);
        v = v * v * (3.0 - 2.0 * v);
        double a = lattice(ix, iy), b = lattice(ix + 1, iy);
        double c = lattice(ix, iy + 1), d = lattice(ix + 1, iy + 1);
        return (a + (b - a) * u) * (1.0 - v) + (c + (d - c) * u) * v;
    }

    private static double lattice(int x, int y) {
        long hash = x * 0x9e3779b97f4a7c15L ^ y * 0xc2b2ae3d27d4eb4fL ^ 0x6a09e667f3bcc909L;
        hash ^= hash >>> 30;
        hash *= 0xbf58476d1ce4e5b9L;
        hash ^= hash >>> 27;
        hash *= 0x94d049bb133111ebL;
        hash ^= hash >>> 31;
        return (hash >>> 11) * 0x1.0p-53 * 2.0 - 1.0;
    }
}
