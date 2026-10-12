package fpv.render;

import java.util.Arrays;

/** Pure, deterministic alpha-grid extrusion; no Arc, GPU or host assets are needed. */
public final class SpriteExtrusionGeometry {
    public static final int STRIDE = 8;
    private SpriteExtrusionGeometry() {}

    public record Result(float[] vertices, int gridWidth, int gridHeight, int opaqueCells,
                         int topQuads, int bottomQuads, int sideQuads) {
        public int vertexCount() { return vertices.length / STRIDE; }
        public long vertexBytes() { return (long)vertices.length * Float.BYTES; }
    }

    public static Result generate(byte[] alpha, int width, int height, int maximumSide, int minimumSide,
                                  int threshold, double minimumCoverage, int maximumVertices) {
        if (alpha == null || width <= 0 || height <= 0 || (long)width * height != alpha.length
            || maximumSide < minimumSide || minimumSide < 1 || threshold < 1 || threshold > 255
            || !Double.isFinite(minimumCoverage) || minimumCoverage <= 0 || minimumCoverage > 1
            || maximumVertices < 36 || maximumVertices > Integer.MAX_VALUE / STRIDE)
            throw new IllegalArgumentException("Invalid silhouette limits");
        int side = maximumSide;
        while (true) {
            Grid grid = sample(alpha, width, height, side, threshold, minimumCoverage);
            try { return mesh(grid, maximumVertices); }
            catch (LimitException limit) {
                if (side <= minimumSide) throw new IllegalArgumentException("Silhouette exceeds vertex budget");
                side = Math.max(minimumSide, side / 2);
            }
        }
    }

    private record Grid(int width, int height, boolean[] solid, float[] sampleU, float[] sampleV, int count) {
        boolean at(int x, int y) { return x >= 0 && y >= 0 && x < width && y < height && solid[x + y * width]; }
    }

    private static Grid sample(byte[] alpha, int w, int h, int maximumSide, int threshold, double coverage) {
        int longest = Math.max(w, h);
        int gw = Math.min(w, Math.max(1, (int)Math.ceil(w * (double)maximumSide / longest)));
        int gh = Math.min(h, Math.max(1, (int)Math.ceil(h * (double)maximumSide / longest)));
        boolean[] solid = new boolean[gw * gh];
        float[] us = new float[solid.length], vs = new float[solid.length];
        int count = 0;
        for (int gy = 0; gy < gh; gy++) for (int gx = 0; gx < gw; gx++) {
            int x0 = gx * w / gw, x1 = (gx + 1) * w / gw;
            int y0 = gy * h / gh, y1 = (gy + 1) * h / gh;
            int opaque = 0, bestX = x0, bestY = y0;
            double nearest = Double.POSITIVE_INFINITY, cx = (x0 + x1 - 1) / 2.0, cy = (y0 + y1 - 1) / 2.0;
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                if ((alpha[x + y * w] & 255) < threshold) continue;
                opaque++;
                double distance = (x - cx) * (x - cx) + (y - cy) * (y - cy);
                if (distance < nearest) { nearest = distance; bestX = x; bestY = y; }
            }
            int index = gx + gy * gw;
            solid[index] = opaque > 0 && opaque / (double)((x1 - x0) * (y1 - y0)) >= coverage;
            if (solid[index]) count++;
            us[index] = (bestX + .5f) / w;
            vs[index] = (bestY + .5f) / h;
        }
        return new Grid(gw, gh, solid, us, vs, count);
    }

    private static Result mesh(Grid g, int cap) {
        Output out = new Output(cap);
        boolean[] used = new boolean[g.solid.length];
        int tops = 0, sides = 0;
        for (int y = 0; y < g.height; y++) for (int x = 0; x < g.width; x++) {
            if (!g.at(x, y) || used[x + y * g.width]) continue;
            int width = 1;
            while (x + width < g.width && g.at(x + width, y) && !used[x + width + y * g.width]) width++;
            int height = 1;
            outer: while (y + height < g.height) {
                for (int xx = x; xx < x + width; xx++)
                    if (!g.at(xx, y + height) || used[xx + (y + height) * g.width]) break outer;
                height++;
            }
            for (int yy = y; yy < y + height; yy++) Arrays.fill(used, x + yy * g.width, x + width + yy * g.width, true);
            float x0 = x / (float)g.width - .5f, x1 = (x + width) / (float)g.width - .5f;
            float z0 = y / (float)g.height - .5f, z1 = (y + height) / (float)g.height - .5f;
            // CCW viewed from outside: top +Y, bottom -Y.
            out.face(x0,.5f,z0, x0,.5f,z1, x1,.5f,z1, x1,.5f,z0, 0,1,0, -1,-1);
            out.face(x0,-.5f,z0, x1,-.5f,z0, x1,-.5f,z1, x0,-.5f,z1, 0,-1,0, -1,-1);
            tops++;
        }
        for (int y = 0; y < g.height; y++) for (int x = 0; x < g.width; x++) {
            if (!g.at(x, y)) continue;
            float x0 = x / (float)g.width - .5f, x1 = (x + 1f) / g.width - .5f;
            float z0 = y / (float)g.height - .5f, z1 = (y + 1f) / g.height - .5f;
            float u = g.sampleU[x + y * g.width], v = g.sampleV[x + y * g.width];
            if (!g.at(x, y - 1)) { out.face(x0,-.5f,z0, x0,.5f,z0, x1,.5f,z0, x1,-.5f,z0, 0,0,-1,u,v); sides++; }
            if (!g.at(x, y + 1)) { out.face(x0,-.5f,z1, x1,-.5f,z1, x1,.5f,z1, x0,.5f,z1, 0,0,1,u,v); sides++; }
            if (!g.at(x - 1, y)) { out.face(x0,-.5f,z0, x0,-.5f,z1, x0,.5f,z1, x0,.5f,z0, -1,0,0,u,v); sides++; }
            if (!g.at(x + 1, y)) { out.face(x1,-.5f,z0, x1,.5f,z0, x1,.5f,z1, x1,-.5f,z1, 1,0,0,u,v); sides++; }
        }
        return new Result(Arrays.copyOf(out.values, out.count), g.width, g.height, g.count, tops, tops, sides);
    }

    /** Row of three world basis vectors: right, up, back. Native yaw0=east,90=north. */
    public static void basis(double yaw, double pitch, double roll, double[] out) {
        if (out == null || out.length < 9 || !Double.isFinite(yaw) || !Double.isFinite(pitch) || !Double.isFinite(roll))
            throw new IllegalArgumentException("Finite angles and nine basis outputs required");
        double a = Math.toRadians(yaw), p = Math.toRadians(pitch), r = Math.toRadians(roll);
        double ca = Math.cos(a), sa = Math.sin(a), cp = Math.cos(p), sp = Math.sin(p), cr = Math.cos(r), sr = Math.sin(r);
        double rx = sa, rz = ca, ux = -ca * sp, uy = cp, uz = sa * sp;
        out[0] = rx * cr - ux * sr; out[1] = -uy * sr; out[2] = rz * cr - uz * sr;
        out[3] = ux * cr + rx * sr; out[4] = uy * cr; out[5] = uz * cr + rz * sr;
        out[6] = -ca * cp; out[7] = -sp; out[8] = sa * cp;
    }

    private static final class LimitException extends RuntimeException {
        LimitException() { super(null, null, false, false); }
    }
    private static final class Output {
        final int cap;
        float[] values;
        int count;
        Output(int maximumVertices) {
            cap = maximumVertices * STRIDE;
            values = new float[Math.min(cap, 36 * STRIDE)];
        }
        void face(float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,
                  float dx,float dy,float dz,float nx,float ny,float nz,float u,float v) {
            int needed = count + 6 * STRIDE;
            if (needed > cap) throw new LimitException();
            if (needed > values.length) values = Arrays.copyOf(values, Math.min(cap, Math.max(needed, values.length * 2)));
            vertex(ax,ay,az,nx,ny,nz,u,v); vertex(bx,by,bz,nx,ny,nz,u,v); vertex(cx,cy,cz,nx,ny,nz,u,v);
            vertex(ax,ay,az,nx,ny,nz,u,v); vertex(cx,cy,cz,nx,ny,nz,u,v); vertex(dx,dy,dz,nx,ny,nz,u,v);
        }
        void vertex(float x,float y,float z,float nx,float ny,float nz,float u,float v) {
            values[count++] = x; values[count++] = y; values[count++] = z;
            values[count++] = nx; values[count++] = ny; values[count++] = nz;
            values[count++] = u < 0 ? x + .5f : u; values[count++] = v < 0 ? z + .5f : v;
        }
    }
}
