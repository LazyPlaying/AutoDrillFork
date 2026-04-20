package autodrill.filler;

import arc.math.geom.Point2;
import arc.struct.ObjectIntMap;
import arc.struct.Seq;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;

import java.util.InputMismatchException;

public class BridgeDrill {
    private static final int bridgeRange = 4;

    public static void fill(Tile tile, Drill drill, Direction direction) {
        if (drill.size != 2) throw new InputMismatchException("Drill must have a size of 2");

        int maxTiles = Util.maxTiles(drill);

        Seq<Tile> tiles = Util.getConnectedTiles(tile, maxTiles);
        Util.expandArea(tiles, drill);
        placeDrillsAndBridges(tile, tiles, drill, direction);
    }

    private static void placeDrillsAndBridges(Tile source, Seq<Tile> tiles, Drill drill, Direction direction) {
        Point2 directionConfig = new Point2(direction.p.x * 3, direction.p.y * 3);

        Seq<Tile> drillTiles = tiles.select(t -> isDrillTile(t) && Util.placeable(t, drill, 0));
        Seq<Tile> bridgeTiles = tiles.select(t -> isBridgeTile(t) && Util.placeable(t, Blocks.itemBridge, 0));

        int minOresPerDrill = Util.minOres(drill);

        drillTiles.retainAll(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = Util.countOre(t, drill);

            if (itemAndCount == null || itemAndCount.key != source.drop() || itemAndCount.value < minOresPerDrill) {
                return false;
            }

            Seq<Tile> neighbors = Util.getNearbyTiles(t.x, t.y, drill);
            neighbors.retainAll(n -> isBridgeTile(n) && Util.placeable(n, Blocks.itemBridge, 0));

            for (Tile neighbor : neighbors) {
                if (bridgeTiles.contains(neighbor)) return true;
            }

            if (!neighbors.isEmpty()) {
                for (Tile neighbor : neighbors) {
                    if (!bridgeTiles.contains(neighbor)) bridgeTiles.add(neighbor);
                }
                return true;
            }

            return false;
        });

        Tile outlet = findOutlet(bridgeTiles, directionConfig, direction);
        if (outlet == null) return;
        bridgeTiles.add(outlet);

        bridgeTiles.sort(t -> t.dst2(outlet.worldx(), outlet.worldy()));

        for (Tile drillTile : drillTiles) {
            BuildPlan buildPlan = new BuildPlan(drillTile.x, drillTile.y, 0, drill);
            Util.addBuildPlan(buildPlan);
        }

        for (Tile bridgeTile : bridgeTiles) {
            Tile neighbor = bridgeTiles.select(t -> isBridgeLink(bridgeTile, t) && t.dst2(outlet) < bridgeTile.dst2(outlet)).min(t -> t.dst2(outlet));

            Point2 config = new Point2();
            if (bridgeTile != outlet && neighbor != null) {
                config = new Point2(neighbor.x - bridgeTile.x, neighbor.y - bridgeTile.y);
            }

            BuildPlan buildPlan = new BuildPlan(bridgeTile.x, bridgeTile.y, 0, Blocks.itemBridge, config);
            Util.addBuildPlan(buildPlan);
        }
    }

    private static Tile findOutlet(Seq<Tile> bridgeTiles, Point2 directionConfig, Direction direction) {
        Seq<Tile> candidates = bridgeTiles.copy();
        candidates.sort(t -> -(direction.p.x == 0 ? t.y * direction.p.y : t.x * direction.p.x));

        for (Tile bridgeTile : candidates) {
            Tile outlet = bridgeTile.nearby(directionConfig);
            if (Util.placeable(outlet, Blocks.itemBridge, 0)) return outlet;
        }

        return null;
    }

    private static boolean isBridgeLink(Tile from, Tile to) {
        int dx = Math.abs(to.x - from.x);
        int dy = Math.abs(to.y - from.y);
        int distance = dx + dy;

        return from != to && (dx == 0 || dy == 0) && distance > 0 && distance <= bridgeRange;
    }

    private static boolean isDrillTile(Tile tile) {
        short x = tile.x;
        short y = tile.y;

        switch (x % 6) {
            case 0:
            case 2:
                if ((y - 1) % 6 == 0) return true;
                break;
            case 1:
                if ((y - 3) % 6 == 0 || (y - 3) % 6 == 2) return true;
                break;
            case 3:
            case 5:
                if ((y - 4) % 6 == 0) return true;
                break;
            case 4:
                if ((y) % 6 == 0 || (y) % 6 == 2) return true;
                break;
        }

        return false;
    }

    private static boolean isBridgeTile(Tile tile) {
        short x = tile.x;
        short y = tile.y;

        return x % 3 == 0 && y % 3 == 0;
    }
}
