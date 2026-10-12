package fpv.terrain;

import fpv.generated.TerrainConfig;
import mindustry.Vars;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import mindustry.world.blocks.environment.StaticTree;
import mindustry.world.blocks.environment.StaticWall;

/** Read-only native-map adapter. Called on the game thread; never changes tiles or save data. */
public final class TerrainElevation {
    private final TerrainHeightField.Parameters parameters;
    private TerrainHeightField field;
    private Tiles sourceTiles;
    private boolean dirty = true;
    private long revision;
    private double buildMilliseconds;

    public TerrainElevation() {
        this(new TerrainHeightField.Parameters(TerrainConfig.MINIMUM_HEIGHT_TILES,
            TerrainConfig.RISE_PER_LAYER, TerrainConfig.MAXIMUM_HEIGHT_TILES,
            (int)TerrainConfig.SMOOTHING_PASSES, TerrainConfig.HEIGHT_VARIATION,
            TerrainConfig.VARIATION_SCALE_TILES));
    }

    public TerrainElevation(TerrainHeightField.Parameters parameters) {
        if (parameters == null) throw new IllegalArgumentException("Terrain parameters required");
        this.parameters = parameters;
    }

    /** Vanilla/map/mod natural wall families; decorative trees and player-built walls are excluded. */
    public boolean naturalWall(Tile tile) {
        return tile != null && tile.build == null && tile.block() instanceof StaticWall &&
            !(tile.block() instanceof StaticTree)&&!fpv.world.NaturalProps.supported(tile.block());
    }

    public void invalidate() { dirty = true; }

    /** Ordinary building placement should not regenerate every mountain. */
    public void invalidate(Tile tile) {
        if (tile == null || field == null || sourceTiles == null || Vars.world == null ||
            sourceTiles != Vars.world.tiles || tile.x < 0 || tile.y < 0 ||
            tile.x >= field.width() || tile.y >= field.height()) {
            dirty = true;
            return;
        }
        if (field.isNatural(tile.x, tile.y) != naturalWall(tile)) dirty = true;
    }

    /** O(1) in unchanged worlds. A changed mask causes one deterministic full field rebuild. */
    public void ensureWorld() {
        Tiles tiles = Vars.world == null ? null : Vars.world.tiles;
        int width = tiles == null ? 0 : tiles.width, height = tiles == null ? 0 : tiles.height;
        if (!dirty && sourceTiles == tiles && field != null && field.width() == width && field.height() == height) return;
        long started = System.nanoTime();
        boolean[] natural = new boolean[width * height];
        if (tiles != null) {
            for (int i = 0; i < natural.length; i++) natural[i] = naturalWall(tiles.geti(i));
        }
        TerrainHeightField next = TerrainHeightField.generate(width, height, natural, parameters);
        sourceTiles = tiles;
        field = next;
        dirty = false;
        revision++;
        buildMilliseconds = (System.nanoTime() - started) / 1_000_000.0;
    }

    public double heightAt(double renderX, double renderZ) { ensureWorld(); return field.heightAt(renderX, renderZ); }
    public float vertexHeight(int cornerX, int cornerY) { ensureWorld(); return field.vertexHeight(cornerX, cornerY); }
    public float tileCornerHeight(int tileX, int tileY, int corner) { ensureWorld(); return field.tileCornerHeight(tileX, tileY, corner); }
    public boolean naturalWall(int tileX, int tileY) { ensureWorld(); return field.isNatural(tileX, tileY); }
    public float maxHeight() { ensureWorld(); return field.maxHeight(); }
    public int naturalWallCount() { ensureWorld(); return field.naturalCount(); }
    public long revision() { ensureWorld(); return revision; }
    /** Dimensions and natural membership only; this is not a serialization hash of the game world. */
    public long mapFingerprint() { ensureWorld(); return field.maskFingerprint(); }
    public double buildMilliseconds() { return buildMilliseconds; }
}
