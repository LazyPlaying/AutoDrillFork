package autodrill.filler;

import arc.Core;
import arc.math.geom.Point2;
import arc.math.geom.Rect;
import arc.struct.ObjectIntMap;
import arc.struct.Queue;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Edges;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;

import static mindustry.Vars.world;

public class Util {
    public static final String activationKeySetting = "auto-drill-activation-key";
    public static final String displayToggleButtonSetting = "auto-drill-display-toggle-button";
    public static final String optimizationQualitySetting = "auto-drill-optimization-quality";
    public static final String placeWaterExtractorsAndPowerNodesSetting = "auto-drill-place-water-extractor-and-power-nodes";

    public static final String mechanicalDrillMaxTilesSetting = "mechanical-drill-max-tiles";
    public static final String mechanicalDrillMinOresSetting = "mechanical-drill-min-ores";
    public static final String pneumaticDrillMaxTilesSetting = "pneumatic-drill-max-tiles";
    public static final String pneumaticDrillMinOresSetting = "pneumatic-drill-min-ores";
    public static final String laserDrillMaxTilesSetting = "laser-drill-max-tiles";
    public static final String laserDrillMinOresSetting = "laser-drill-min-ores";
    public static final String airblastDrillMaxTilesSetting = "airblast-drill-max-tiles";
    public static final String airblastDrillMinOresSetting = "airblast-drill-min-ores";
    public static final String plasmaBoreMaxTilesSetting = "plasma-bore-max-tiles";

    protected static Seq<Tile> getNearbyTiles(int x, int y, Block block) {
        return getNearbyTiles(x, y, block.size);
    }

    protected static Seq<Tile> getNearbyTiles(int x, int y, int size) {
        Seq<Tile> nearbyTiles = new Seq<>();

        Point2[] nearby = Edges.getEdges(size);
        for (Point2 point2 : nearby) {
            Tile t = world.tile(x + point2.x, y + point2.y);
            if (t != null) nearbyTiles.add(t);
        }

        return nearbyTiles;
    }

    protected static Seq<Tile> getNearbyTiles(int x, int y, int size1, int size2) {
        int offset1 = (size1 % 2 == 1 && size2 % 2 == 0) ? 1 : 0;
        int offset2 = ((size2 * 2 - 1) / 2);

        return getNearbyTiles(x - offset1, y - offset1, size1 + offset2);
    }

    protected static ObjectIntMap.Entry<Item> countOre(Tile tile, Drill drill) {
        Item item;
        int count;

        ObjectIntMap<Item> oreCount = new ObjectIntMap<>();
        Seq<Item> itemArray = new Seq<>();

        for (Tile other : tile.getLinkedTilesAs(drill, new Seq<>())) {
            if (drill.canMine(other)) {
                oreCount.increment(drill.getDrop(other), 0, 1);
            }
        }

        for (Item i : oreCount.keys()) {
            itemArray.add(i);
        }

        itemArray.sort((item1, item2) -> {
            int type = Boolean.compare(!item1.lowPriority, !item2.lowPriority);
            if (type != 0) return type;
            int amounts = Integer.compare(oreCount.get(item1, 0), oreCount.get(item2, 0));
            if (amounts != 0) return amounts;
            return Integer.compare(item1.id, item2.id);
        });

        if (itemArray.size == 0) {
            return null;
        }

        item = itemArray.peek();
        count = oreCount.get(itemArray.peek(), 0);

        ObjectIntMap.Entry<Item> itemAndCount = new ObjectIntMap.Entry<>();
        itemAndCount.key = item;
        itemAndCount.value = count;

        return itemAndCount;
    }

    protected static void expandArea(Seq<Tile> tiles, int radius) {
        Seq<Tile> expandedTiles = new Seq<>();

        for (Tile tile : tiles) {
            for (int dx = -radius; dx < radius; dx++) {
                for (int dy = -radius; dy < radius; dy++) {
                    if (dx == 0 && dy == 0) continue;

                    Tile nearby = tile.nearby(dx, dy);
                    if (nearby == null) continue;

                    if (!tiles.contains(nearby) && !expandedTiles.contains(nearby)) {
                        expandedTiles.add(nearby);
                    }
                }
            }
        }

        tiles.add(expandedTiles);
    }

    protected static void expandArea(Seq<Tile> tiles, Block block) {
        Seq<Tile> expandedTiles = new Seq<>();
        int minOffset = -block.size / 2;
        int maxOffset = (block.size - 1) / 2;

        for (Tile tile : tiles) {
            for (int dx = minOffset; dx <= maxOffset; dx++) {
                for (int dy = minOffset; dy <= maxOffset; dy++) {
                    if (dx == 0 && dy == 0) continue;

                    Tile nearby = tile.nearby(dx, dy);
                    if (nearby == null) continue;

                    if (!tiles.contains(nearby) && !expandedTiles.contains(nearby)) {
                        expandedTiles.add(nearby);
                    }
                }
            }
        }

        tiles.add(expandedTiles);
    }

    protected static String maxTilesSetting(Drill drill) {
        if (drill == Blocks.mechanicalDrill) return mechanicalDrillMaxTilesSetting;
        if (drill == Blocks.pneumaticDrill) return pneumaticDrillMaxTilesSetting;
        if (drill == Blocks.laserDrill) return laserDrillMaxTilesSetting;
        if (drill == Blocks.blastDrill) return airblastDrillMaxTilesSetting;

        return drill.size <= 3 ? laserDrillMaxTilesSetting : airblastDrillMaxTilesSetting;
    }

    protected static String minOresSetting(Drill drill) {
        if (drill == Blocks.mechanicalDrill) return mechanicalDrillMinOresSetting;
        if (drill == Blocks.pneumaticDrill) return pneumaticDrillMinOresSetting;
        if (drill == Blocks.laserDrill) return laserDrillMinOresSetting;
        if (drill == Blocks.blastDrill) return airblastDrillMinOresSetting;

        return drill.size <= 3 ? laserDrillMinOresSetting : airblastDrillMinOresSetting;
    }

    protected static int maxTiles(Drill drill) {
        return Core.settings.getInt(maxTilesSetting(drill), defaultMaxTiles(drill));
    }

    protected static int minOres(Drill drill) {
        return Core.settings.getInt(minOresSetting(drill), defaultMinOres(drill));
    }

    protected static int defaultMaxTiles(Drill drill) {
        if (drill == Blocks.mechanicalDrill) return 200;
        if (drill == Blocks.pneumaticDrill) return 150;
        return 100;
    }

    protected static int defaultMinOres(Drill drill) {
        if (drill == Blocks.mechanicalDrill) return 1;
        if (drill == Blocks.pneumaticDrill) return 2;
        if (drill == Blocks.laserDrill) return 5;
        if (drill == Blocks.blastDrill) return 9;

        return drill.size <= 3 ? 5 : 9;
    }

    protected static boolean placeable(Tile tile, Block block, int rotation) {
        return tile != null && new BuildPlan(tile.x, tile.y, rotation, block).placeable(Vars.player.team());
    }

    protected static void addBuildPlan(BuildPlan buildPlan) {
        if (buildPlan != null && buildPlan.placeable(Vars.player.team())) {
            Vars.player.unit().addBuild(buildPlan);
        }
    }

    protected static Seq<Tile> getConnectedTiles(Tile tile, int maxTiles) {
        Queue<Tile> queue = new Queue<>();
        Seq<Tile> tiles = new Seq<>();
        Seq<Tile> visited = new Seq<>();

        queue.addLast(tile);

        Item sourceItem = tile.drop();

        while (!queue.isEmpty() && tiles.size < maxTiles) {
            Tile currentTile = queue.removeFirst();

            if (!Build.validPlace(Blocks.copperWall.environmentBuildable() ? Blocks.copperWall : Blocks.berylliumWall, Vars.player.team(), currentTile.x, currentTile.y, 0) || visited.contains(currentTile))
                continue;

            if (currentTile.drop() == sourceItem) {
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        if (!(x == 0 && y == 0)) {
                            Tile neighbor = currentTile.nearby(x, y);
                            if (neighbor == null) continue;

                            if (!visited.contains(neighbor)) {
                                queue.addLast(neighbor);
                            }
                        }
                    }
                }

                tiles.add(currentTile);
            }

            visited.add(currentTile);
        }

        tiles.sort(Tile::pos);

        return tiles;
    }

    protected static Rect getBlockRect(Tile tile, Block block) {
        int offset = (block.size - 1) / 2;
        return new Rect(tile.x - offset, tile.y - offset, block.size, block.size);
    }

    protected static Point2 tileToPoint2(Tile tile) {
        return new Point2(tile.x, tile.y);
    }
}
