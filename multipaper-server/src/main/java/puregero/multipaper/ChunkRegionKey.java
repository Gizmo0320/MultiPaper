package puregero.multipaper;

public class ChunkRegionKey {
    private final String name;
    private final String path;
    private final int x;
    private final int z;

    private int hashCodeCache;

    public ChunkRegionKey(String name, String path, int x, int z) {
        this.name = name;
        this.path = path;
        this.x = x;
        this.z = z;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof ChunkRegionKey) {
            return ((ChunkRegionKey) other).name.equals(name)
                    && ((ChunkRegionKey) other).path.equals(path)
                    && ((ChunkRegionKey) other).x == x
                    && ((ChunkRegionKey) other).z == z;
        }

        return super.equals(other);
    }

    @Override
    public int hashCode() {
        if (hashCodeCache != 0) return hashCodeCache;

        // Taken from ChunkCoordIntPair
        int i = 1664525 * this.x + 1013904223;
        int j = 1664525 * (this.z ^ -559038737) + 1013904223;

        return hashCodeCache = name.hashCode() ^ path.hashCode() ^ i ^ j;
    }
}

