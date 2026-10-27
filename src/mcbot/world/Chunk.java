package mcbot.world;

/**
 * A 16x16 chunk column. Sections are stored lazily; a null section is all air.
 */
public final class Chunk {
    public final int chunkX;
    public final int chunkZ;
    public final int minY;
    public final int height;
    public final int sectionCount;
    private final char[][] sections;

    public Chunk(int chunkX, int chunkZ, int minY, int height) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.height = height;
        this.sectionCount = height / 16;
        this.sections = new char[sectionCount][];
    }

    public char[] section(int index) {
        if (index < 0 || index >= sectionCount) return null;
        return sections[index];
    }

    public void setSection(int index, char[] blocks) {
        if (index < 0 || index >= sectionCount) return;
        sections[index] = blocks;
    }

    public int getBlock(int localX, int worldY, int localZ) {
        if (worldY < minY || worldY >= minY + height) return 0;
        int sectionIndex = (worldY - minY) >> 4;
        char[] section = sections[sectionIndex];
        if (section == null) return 0;
        int localY = (worldY - minY) & 15;
        return section[localY * 256 + localZ * 16 + localX];
    }

    public void setBlock(int localX, int worldY, int localZ, int state) {
        if (worldY < minY || worldY >= minY + height) return;
        int sectionIndex = (worldY - minY) >> 4;
        char[] section = sections[sectionIndex];
        if (section == null) {
            if (state == 0) return;
            section = new char[4096];
            sections[sectionIndex] = section;
        }
        int localY = (worldY - minY) & 15;
        section[localY * 256 + localZ * 16 + localX] = (char) state;
    }
}
