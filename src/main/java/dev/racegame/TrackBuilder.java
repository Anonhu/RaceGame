package dev.racegame;

import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;

/** Builds the playable neon ring directly out of Minecraft blocks. */
public final class TrackBuilder {
    public static final int SURFACE_Y = 63;
    public static final double RADIUS_X = 48.0;
    public static final double RADIUS_Z = 32.0;
    public static final double ROAD_HALF_WIDTH = 0.22;
    public static final int PLATFORM_X = 78;
    public static final int PLATFORM_Z = 55;
    public static final Pos LOBBY = new Pos(0.5, SURFACE_Y + 1, 0.5, 0, 0);

    private static final int CHECKPOINT_COUNT = 4;

    private TrackBuilder() {
    }

    public static void generate(GenerationUnit unit) {
        BlockVec start = unit.absoluteStart();
        BlockVec end = unit.absoluteEnd();
        int minX = Math.max(start.blockX(), -PLATFORM_X);
        int maxX = Math.min(end.blockX(), PLATFORM_X + 1);
        int minZ = Math.max(start.blockZ(), -PLATFORM_Z);
        int maxZ = Math.min(end.blockZ(), PLATFORM_Z + 1);
        if (minX >= maxX || minZ >= maxZ) return;

        var blocks = unit.modifier();
        blocks.fillHeight(60, SURFACE_Y, Block.DEEPSLATE_BRICKS);

        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                double radius = normalizedRadius(x, z);
                blocks.setBlock(x, SURFACE_Y, z, surfaceBlock(x, z, radius));

                for (int y = SURFACE_Y + 1; y <= SURFACE_Y + 5; y++) {
                    Block gateBlock = gateBlock(x, y, z);
                    if (gateBlock != Block.AIR) blocks.setBlock(x, y, z, gateBlock);
                }

                if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                    for (int y = SURFACE_Y + 5; y <= SURFACE_Y + 10; y++) {
                        Block crystal = y == SURFACE_Y + 5 || y == SURFACE_Y + 10
                                ? Block.AMETHYST_BLOCK
                                : Block.SEA_LANTERN;
                        blocks.setBlock(x, y, z, crystal);
                    }
                }
            }
        }
    }

    public static Pos gridPosition(int slot) {
        return vehiclePosition(startAngleForSlot(slot), laneOffsetForSlot(slot));
    }

    public static double laneOffsetForSlot(int slot) {
        double[] offsets = {-4.2, -1.4, 1.4, 4.2};
        return offsets[Math.floorMod(slot, offsets.length)];
    }

    public static double startAngleForSlot(int slot) {
        return -Math.PI / 2.0 - Math.max(0, slot / 4) * 0.06;
    }

    public static Pos vehiclePosition(double angle, double laneOffset) {
        double normalX = Math.cos(angle) / RADIUS_X;
        double normalZ = Math.sin(angle) / RADIUS_Z;
        double normalLength = Math.hypot(normalX, normalZ);
        normalX /= normalLength;
        normalZ /= normalLength;

        double x = RADIUS_X * Math.cos(angle) + laneOffset * normalX;
        double z = RADIUS_Z * Math.sin(angle) + laneOffset * normalZ;
        double tangentX = -RADIUS_X * Math.sin(angle);
        double tangentZ = RADIUS_Z * Math.cos(angle);
        float yaw = (float) Math.toDegrees(Math.atan2(-tangentX, tangentZ));
        return new Pos(x + 0.5, SURFACE_Y + 1, z + 0.5, yaw, 0);
    }

    public static Pos checkpointPosition(int index) {
        double angle = checkpointAngle(index);
        return new Pos(RADIUS_X * Math.cos(angle), SURFACE_Y + 1, RADIUS_Z * Math.sin(angle));
    }

    public static boolean isOnTrack(double x, double z) {
        return Math.abs(normalizedRadius(x, z) - 1.0) <= ROAD_HALF_WIDTH + 0.035;
    }

    public static double checkpointAngle(int index) {
        return switch (Math.floorMod(index, CHECKPOINT_COUNT)) {
            case 0 -> 0.0;
            case 1 -> Math.PI / 2.0;
            case 2 -> Math.PI;
            default -> -Math.PI / 2.0;
        };
    }

    private static double normalizedRadius(double x, double z) {
        double normalizedX = x / RADIUS_X;
        double normalizedZ = z / RADIUS_Z;
        return Math.sqrt(normalizedX * normalizedX + normalizedZ * normalizedZ);
    }

    private static Block surfaceBlock(int x, int z, double radius) {
        // A checkered stripe crosses the full road just before the north gate.
        if (Math.abs(x) <= 1 && z >= -(int) RADIUS_Z - 7 && z <= -(int) RADIUS_Z + 7) {
            return Math.floorMod(z, 2) == 0 ? Block.WHITE_CONCRETE : Block.CYAN_CONCRETE;
        }

        double radialOffset = Math.abs(radius - 1.0);
        if (radialOffset <= ROAD_HALF_WIDTH) {
            if (radialOffset >= ROAD_HALF_WIDTH - 0.028) {
                int pulse = Math.floorMod((int) (Math.atan2(z / RADIUS_Z, x / RADIUS_X) * 28), 4);
                return pulse == 0 ? Block.SEA_LANTERN : (pulse < 2 ? Block.CYAN_CONCRETE : Block.MAGENTA_CONCRETE);
            }
            if (radialOffset < 0.018) {
                int dash = Math.floorMod((int) (Math.atan2(z / RADIUS_Z, x / RADIUS_X) * 100), 14);
                if (dash < 6) return Block.LIME_CONCRETE;
            }
            return (Math.floorMod(x + z, 7) == 0) ? Block.POLISHED_BLACKSTONE : Block.BLACK_CONCRETE;
        }

        if (radius < 0.61) {
            if (radius < 0.075) return Block.AMETHYST_BLOCK;
            if (Math.floorMod(x * 3 + z * 5, 23) == 0) return Block.PURPLE_CONCRETE;
            if (Math.floorMod(x, 12) == 0 || Math.floorMod(z, 12) == 0) return Block.POLISHED_BLACKSTONE_BRICKS;
            return Block.BLACKSTONE;
        }

        if (Math.abs(radius - 1.30) < 0.035) {
            return Math.floorMod(x + z, 2) == 0 ? Block.PURPLE_CONCRETE : Block.MAGENTA_CONCRETE;
        }

        if (Math.abs(x) >= PLATFORM_X - 2 || Math.abs(z) >= PLATFORM_Z - 2) {
            return Math.floorMod(x + z, 2) == 0 ? Block.SEA_LANTERN : Block.CYAN_CONCRETE;
        }

        if (Math.floorMod(x, 16) == 0 || Math.floorMod(z, 16) == 0) return Block.POLISHED_BLACKSTONE_BRICKS;
        return Block.BLACKSTONE;
    }

    private static Block gateBlock(int x, int y, int z) {
        for (int checkpoint = 0; checkpoint < CHECKPOINT_COUNT; checkpoint++) {
            double angle = checkpointAngle(checkpoint);
            double centerX = RADIUS_X * Math.cos(angle);
            double centerZ = RADIUS_Z * Math.sin(angle);

            // The gate runs across the track, perpendicular to the oval's tangent.
            double tangentX = -RADIUS_X * Math.sin(angle);
            double tangentZ = RADIUS_Z * Math.cos(angle);
            double tangentLength = Math.hypot(tangentX, tangentZ);
            tangentX /= tangentLength;
            tangentZ /= tangentLength;

            double normalX = Math.cos(angle) / RADIUS_X;
            double normalZ = Math.sin(angle) / RADIUS_Z;
            double normalLength = Math.hypot(normalX, normalZ);
            normalX /= normalLength;
            normalZ /= normalLength;
            double gateHalfWidth = ROAD_HALF_WIDTH / normalLength + 1.0;

            double dx = x + 0.5 - centerX;
            double dz = z + 0.5 - centerZ;
            double alongTrack = dx * tangentX + dz * tangentZ;
            double acrossTrack = dx * normalX + dz * normalZ;
            if (Math.abs(alongTrack) > 0.72 || Math.abs(acrossTrack) > gateHalfWidth) continue;

            boolean pillar = Math.abs(Math.abs(acrossTrack) - (gateHalfWidth - 0.7)) < 0.85;
            if (pillar && y <= SURFACE_Y + 5) {
                return y == SURFACE_Y + 3 ? Block.SEA_LANTERN : gateAccent(checkpoint);
            }
            if (y == SURFACE_Y + 5) {
                return checkpoint == 3 ? Block.LIME_STAINED_GLASS : Block.CYAN_STAINED_GLASS;
            }
        }
        return Block.AIR;
    }

    private static Block gateAccent(int checkpoint) {
        return switch (checkpoint) {
            case 0 -> Block.CYAN_CONCRETE;
            case 1 -> Block.MAGENTA_CONCRETE;
            case 2 -> Block.PURPLE_CONCRETE;
            default -> Block.LIME_CONCRETE;
        };
    }
}
