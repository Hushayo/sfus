package mcbot.registry;

/**
 * Collision shape classes, with the boxes a physics step needs.
 *
 * Vanilla has one shape per block state built from voxel boxes. A bot does not
 * need the exact voxel set, it needs the answers to four questions:
 *
 *   can I stand where this block is?   -> {@link #empty}
 *   can I stand on top of it, and how high is the top?  -> {@link #topHeight}
 *   does it stop me walking into it?   -> {@link #blocksMovement}
 *   does standing in it hurt, or can I climb it?  -> {@link #hazard} / {@link #climbable}
 *
 * Partial blocks that a player cannot walk through (chests, hoppers, anvils,
 * cauldrons) are deliberately modelled as {@link #FULL}: for movement they are
 * walls, and pretending otherwise makes a pathfinder route through furniture.
 */
public enum Shape {
    /** No collision: air, plants, torches, signs, carpets, liquids. */
    EMPTY(new double[0][], false, 0.0),
    FULL(new double[][]{{0, 0, 0, 1, 1, 1}}, true, 1.0),
    SLAB_BOTTOM(new double[][]{{0, 0, 0, 1, 0.5, 1}}, true, 0.5),
    SLAB_TOP(new double[][]{{0, 0.5, 0, 1, 1, 1}}, true, 1.0),
    /** Bottom slab plus the step a player actually walks up. */
    STAIRS(new double[][]{{0, 0, 0, 1, 0.5, 1}}, true, 1.0),
    /** Fences, walls and panes: too tall to jump, thin but impassable. */
    FENCE(new double[][]{{0, 0, 0, 1, 1.5, 1}}, true, 1.5),
    /** A closed door or trapdoor. */
    DOOR(new double[][]{{0, 0, 0, 1, 1, 1}}, true, 1.0),
    CARPET(new double[][]{{0, 0, 0, 1, 0.0625, 1}}, false, 0.0625),
    SNOW_LAYER(new double[][]{{0, 0, 0, 1, 0.125, 1}}, false, 0.125);

    private final double[][] boxes;
    private final boolean blocksMovement;
    private final double topHeight;

    Shape(double[][] boxes, boolean blocksMovement, double topHeight) {
        this.boxes = boxes;
        this.blocksMovement = blocksMovement;
        this.topHeight = topHeight;
    }

    /** Boxes as {minX,minY,minZ,maxX,maxY,maxZ}, relative to the block corner. */
    public double[][] boxes() { return boxes; }

    /** True when a player cannot occupy this block. */
    public boolean blocksMovement() { return blocksMovement; }

    /** Height of the walkable surface, 0 when there is nothing to stand on. */
    public double topHeight() { return topHeight; }

    public boolean empty() { return boxes.length == 0; }
}
