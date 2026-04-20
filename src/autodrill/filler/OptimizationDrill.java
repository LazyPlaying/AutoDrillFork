package autodrill.filler;

import arc.Core;
import arc.math.geom.Rect;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;

public class OptimizationDrill {
    private static final int pathPadding = 12;

    public static void fill(Tile tile, Drill drill) {
        fill(tile, drill, true);
    }

    public static void fill(Tile tile, Drill drill, Direction outputDirection) {
        fill(tile, drill, outputDirection, true);
    }

    public static void fill(Tile tile, Drill drill, boolean waterExtractorsAndPowerNodes) {
        fill(tile, drill, null, waterExtractorsAndPowerNodes);
    }

    public static void fill(Tile tile, Drill drill, Direction outputDirection, boolean waterExtractorsAndPowerNodes) {
        int maxTiles = Util.maxTiles(drill);

        Seq<Tile> tiles = Util.getConnectedTiles(tile, maxTiles);
        Util.expandArea(tiles, drill);

        int minOresPerDrill = Util.minOres(drill);

        Item sourceItem = drill.getDrop(tile);
        if (sourceItem == null) return;

        ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount = new ObjectMap<>();
        for (Tile t : tiles) {
            tilesItemAndCount.put(t, Util.countOre(t, drill));
        }

        tiles.retainAll(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            return itemAndCount != null && itemAndCount.key == sourceItem && itemAndCount.value >= minOresPerDrill && Build.validPlace(drill, Vars.player.team(), t.x, t.y, 0);
        });

        Seq<Tile> protectedTiles = tiles.select(t -> !coversForeignResource(t, drill, sourceItem));
        if (!protectedTiles.isEmpty()) {
            tiles = protectedTiles;
        }

        tiles.sort(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            return itemAndCount == null ? Integer.MIN_VALUE : -itemAndCount.value;
        });

        Seq<Tile> selection = new Seq<>();

        int maxTries = Core.settings.getInt(Util.optimizationQualitySetting, 2) * 1000;

        recursiveMaxSearch(tiles, drill, tilesItemAndCount, selection, new Seq<>(), 0, new Seq<>(), maxTries, 0, 0);

        Seq<Rect> reservedRects = getDrillRects(selection, drill);
        OutputNetwork outputNetwork = null;
        if (outputDirection != null) {
            outputNetwork = buildOutputNetwork(selection, drill, sourceItem, outputDirection, reservedRects);
            if (outputNetwork != null) {
                selection.retainAll(outputNetwork.routedDrills::contains);
                reservedRects = getDrillRects(selection, drill);
                reservedRects.add(outputNetwork.conveyorRects);
            } else {
                selection.clear();
            }
        }

        if (waterExtractorsAndPowerNodes && Core.settings.getBool(Util.placeWaterExtractorsAndPowerNodesSetting, true))
            placeWaterExtractorsAndPowerNodes(selection, drill, reservedRects, sourceItem);

        if (outputNetwork != null && !selection.isEmpty()) {
            outputNetwork.placePlans();
        }

        for (Tile t : selection) {
            BuildPlan buildPlan = new BuildPlan(t.x, t.y, 0, drill);
            Util.addBuildPlan(buildPlan);
        }
    }

    private static int recursiveMaxSearch(Seq<Tile> tiles, Drill drill, ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount, Seq<Tile> selection, Seq<Rect> rects, int sum, Seq<Integer> triesPerLevel, final int maxTries, final int level, final int startIndex) {
        int max = sum;
        Seq<Tile> maxSelection = selection.copy();

        if (triesPerLevel.size < level + 1) {
            triesPerLevel.setSize(level + 1);
            triesPerLevel.set(level, 0);
        }

        for (int i = startIndex; i < tiles.size; i++) {
            Tile tile = tiles.get(i);
            Rect rect = Util.getBlockRect(tile, drill);

            if ((rects.isEmpty() || rects.find(r -> r.overlaps(rect)) == null) && Build.validPlace(drill, Vars.player.team(), tile.x, tile.y, 0)) {
                int newSum = sum + tilesItemAndCount.get(tile).value;

                Seq<Tile> newSelection = selection.copy().add(tile);
                Seq<Rect> newRects = rects.copy().add(rect);

                int newMax = recursiveMaxSearch(tiles, drill, tilesItemAndCount, newSelection, newRects, newSum, triesPerLevel, maxTries, level + 1, i + 1);

                if (newMax > max) {
                    max = newMax;
                    maxSelection = newSelection.copy();
                }

                triesPerLevel.set(level, triesPerLevel.get(level) + 1);
                if (triesPerLevel.get(level) >= maxTries / Math.pow(2, level + 1)) break;
            }
        }

        selection.clear();
        selection.addAll(maxSelection);

        return max;
    }

    private static OutputNetwork buildOutputNetwork(Seq<Tile> selection, Drill drill, Item sourceItem, Direction outputDirection, Seq<Rect> blockedRects) {
        if (selection.isEmpty()) return null;

        Block conveyor = outputConveyor();
        Bounds bounds = getBounds(selection, drill);
        Tile outlet = findOutlet(bounds, conveyor, outputDirection, sourceItem, blockedRects);
        if (outlet == null) return null;

        OutputNetwork network = new OutputNetwork(conveyor, outputDirection);
        network.addOutlet(outlet);

        Seq<Tile> drillsToRoute = selection.copy();
        drillsToRoute.sort(t -> -t.dst2(outlet));

        for (Tile drillTile : drillsToRoute) {
            Seq<Tile> path = findPathToNetwork(drillTile, drill, conveyor, sourceItem, outputDirection, bounds, blockedRects, network);
            if (path == null) continue;

            for (int i = 0; i < path.size - 1; i++) {
                network.addConveyor(path.get(i), path.get(i + 1));
            }

            network.routedDrills.add(drillTile);
        }

        return network.routedDrills.isEmpty() ? null : network;
    }

    private static Seq<Tile> findPathToNetwork(Tile drillTile, Drill drill, Block conveyor, Item sourceItem, Direction outputDirection, Bounds bounds, Seq<Rect> blockedRects, OutputNetwork network) {
        Seq<Tile> starts = Util.getNearbyTiles(drillTile.x, drillTile.y, drill);
        starts.retainAll(t -> network.contains(t) || canPlaceConveyor(t, conveyor, blockedRects, sourceItem, false));
        starts.sort(t -> outputDirection.primaryAxis(Util.tileToPoint2(t)) * -outputDirection.primaryAxis(Util.tileToPoint2(network.outlet)) + t.dst2(network.outlet));

        for (Tile start : starts) {
            Seq<Tile> path = findPath(start, conveyor, sourceItem, bounds, blockedRects, network, false);
            if (path != null) return path;
        }

        return null;
    }

    private static Seq<Tile> findPath(Tile start, Block conveyor, Item sourceItem, Bounds bounds, Seq<Rect> blockedRects, OutputNetwork network, boolean allowForeignOre) {
        ArrayDeque<Tile> queue = new ArrayDeque<>();
        HashMap<Integer, Integer> parent = new HashMap<>();
        HashMap<Integer, Tile> visited = new HashMap<>();

        queue.addLast(start);
        parent.put(start.pos(), null);
        visited.put(start.pos(), start);

        while (!queue.isEmpty()) {
            Tile current = queue.removeFirst();
            if (network.contains(current)) {
                return reconstructPath(current.pos(), parent, visited);
            }

            for (Direction direction : Direction.values()) {
                Tile next = current.nearby(direction.p);
                if (next == null || visited.containsKey(next.pos()) || !insidePathBounds(next, bounds)) continue;

                boolean target = network.contains(next);
                if (!target && !canPlaceConveyor(next, conveyor, blockedRects, sourceItem, allowForeignOre)) continue;

                parent.put(next.pos(), current.pos());
                visited.put(next.pos(), next);

                if (target) {
                    return reconstructPath(next.pos(), parent, visited);
                }

                queue.addLast(next);
            }
        }

        return null;
    }

    private static Seq<Tile> reconstructPath(int targetPos, HashMap<Integer, Integer> parent, HashMap<Integer, Tile> visited) {
        ArrayList<Tile> reversed = new ArrayList<>();
        Integer current = targetPos;

        while (current != null) {
            reversed.add(visited.get(current));
            current = parent.get(current);
        }

        Seq<Tile> path = new Seq<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }

        return path;
    }

    private static Tile findOutlet(Bounds bounds, Block conveyor, Direction outputDirection, Item sourceItem, Seq<Rect> blockedRects) {
        int center = outputDirection.p.x == 0 ? (bounds.minX + bounds.maxX) / 2 : (bounds.minY + bounds.maxY) / 2;
        int span = outputDirection.p.x == 0 ? bounds.maxX - bounds.minX + 1 : bounds.maxY - bounds.minY + 1;
        int maxOffset = span / 2 + pathPadding;

        for (int distance = 1; distance <= pathPadding; distance++) {
            for (int offsetStep = 0; offsetStep <= maxOffset; offsetStep++) {
                int[] offsets = offsetStep == 0 ? new int[]{0} : new int[]{offsetStep, -offsetStep};
                for (int offset : offsets) {
                    Tile outlet = outletTile(bounds, outputDirection, center + offset, distance);
                    if (canPlaceConveyor(outlet, conveyor, blockedRects, sourceItem, false)) return outlet;
                }
            }
        }

        return null;
    }

    private static Tile outletTile(Bounds bounds, Direction outputDirection, int secondary, int distance) {
        switch (outputDirection) {
            case RIGHT:
                return Vars.world.tile(bounds.maxX + distance, secondary);
            case UP:
                return Vars.world.tile(secondary, bounds.maxY + distance);
            case LEFT:
                return Vars.world.tile(bounds.minX - distance, secondary);
            default:
                return Vars.world.tile(secondary, bounds.minY - distance);
        }
    }

    private static boolean canPlaceConveyor(Tile tile, Block conveyor, Seq<Rect> blockedRects, Item sourceItem, boolean allowForeignOre) {
        if (tile == null) return false;

        Rect rect = Util.getBlockRect(tile, conveyor);
        if (blockedRects.find(r -> r.overlaps(rect)) != null) return false;
        if (!allowForeignOre && hasForeignOre(rect, sourceItem)) return false;

        return new BuildPlan(tile.x, tile.y, 0, conveyor).placeable(Vars.player.team());
    }

    private static boolean insidePathBounds(Tile tile, Bounds bounds) {
        return tile.x >= bounds.minX - pathPadding &&
                tile.x <= bounds.maxX + pathPadding &&
                tile.y >= bounds.minY - pathPadding &&
                tile.y <= bounds.maxY + pathPadding;
    }

    private static Block outputConveyor() {
        return Blocks.titaniumConveyor.environmentBuildable() ? Blocks.titaniumConveyor : Blocks.conveyor;
    }

    private static boolean coversForeignResource(Tile tile, Drill drill, Item sourceItem) {
        for (Tile other : tile.getLinkedTilesAs(drill, new Seq<>())) {
            if (!drill.canMine(other)) continue;

            Item drop = drill.getDrop(other);
            if (drop != null && drop != sourceItem) return true;
        }

        return false;
    }

    private static boolean hasForeignOre(Rect rect, Item sourceItem) {
        int minX = (int) rect.x;
        int minY = (int) rect.y;
        int maxX = (int) (rect.x + rect.width - 1);
        int maxY = (int) (rect.y + rect.height - 1);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile == null) continue;

                Item drop = tile.drop();
                if (drop != null && drop != sourceItem) return true;
            }
        }

        return false;
    }

    private static Seq<Rect> getDrillRects(Seq<Tile> selection, Drill drill) {
        Seq<Rect> rects = new Seq<>();

        for (Tile t : selection) {
            rects.add(Util.getBlockRect(t, drill));
        }

        return rects;
    }

    private static Bounds getBounds(Seq<Tile> selection, Drill drill) {
        Bounds bounds = new Bounds();

        for (Tile t : selection) {
            Rect rect = Util.getBlockRect(t, drill);
            bounds.include(rect);
        }

        return bounds;
    }

    private static void placeWaterExtractorsAndPowerNodes(Seq<Tile> selection, Drill drill, Seq<Rect> reservedRects, Item sourceItem) {
        Seq<Rect> rects = reservedRects.copy();

        Seq<Tile> waterExtractorTiles = new Seq<>();
        Seq<Tile> powerNodeTiles = new Seq<>();

        for (Tile t : selection) {
            Seq<Tile> nearby = Util.getNearbyTiles(t.x, t.y, drill.size, Blocks.waterExtractor.size);

            for (Tile n : nearby) {
                Rect waterExtractorRect = Util.getBlockRect(n, Blocks.waterExtractor);
                BuildPlan buildPlan = new BuildPlan(n.x, n.y, 0, Blocks.waterExtractor);

                if (buildPlan.placeable(Vars.player.team()) && rects.find(r -> r.overlaps(waterExtractorRect)) == null && !hasForeignOre(waterExtractorRect, sourceItem)) {
                    waterExtractorTiles.add(n);
                    rects.add(waterExtractorRect);
                    break;
                }
            }
        }

        for (Tile t : selection) {
            Seq<Tile> nearby = Util.getNearbyTiles(t.x, t.y, drill.size, Blocks.powerNode.size);

            for (Tile n : nearby) {
                Rect powerNodeRect = Util.getBlockRect(n, Blocks.powerNode);
                BuildPlan buildPlan = new BuildPlan(n.x, n.y, 0, Blocks.powerNode);

                if (buildPlan.placeable(Vars.player.team()) && rects.find(r -> r.overlaps(powerNodeRect)) == null && !hasForeignOre(powerNodeRect, sourceItem)) {
                    powerNodeTiles.add(n);
                    rects.add(powerNodeRect);
                    break;
                }
            }
        }

        for (Tile waterExtractorTile : waterExtractorTiles) {
            BuildPlan buildPlan = new BuildPlan(waterExtractorTile.x, waterExtractorTile.y, 0, Blocks.waterExtractor);
            Util.addBuildPlan(buildPlan);
        }

        for (Tile powerNodeTile : powerNodeTiles) {
            BuildPlan buildPlan = new BuildPlan(powerNodeTile.x, powerNodeTile.y, 0, Blocks.powerNode);
            Util.addBuildPlan(buildPlan);
        }
    }

    private static class OutputNetwork {
        final Block conveyor;
        final Direction outputDirection;
        final Seq<Tile> conveyorTiles = new Seq<>();
        final Seq<Rect> conveyorRects = new Seq<>();
        final Seq<Tile> routedDrills = new Seq<>();
        final ObjectMap<Tile, Tile> nextByTile = new ObjectMap<>();
        Tile outlet;

        OutputNetwork(Block conveyor, Direction outputDirection) {
            this.conveyor = conveyor;
            this.outputDirection = outputDirection;
        }

        void addOutlet(Tile tile) {
            outlet = tile;
            addConveyor(tile, null);
        }

        void addConveyor(Tile tile, Tile next) {
            if (!conveyorTiles.contains(tile)) {
                conveyorTiles.add(tile);
                conveyorRects.add(Util.getBlockRect(tile, conveyor));
            }

            if (next != null) {
                nextByTile.put(tile, next);
            }
        }

        boolean contains(Tile tile) {
            return conveyorTiles.contains(tile);
        }

        void placePlans() {
            for (Tile conveyorTile : conveyorTiles) {
                Tile next = nextByTile.get(conveyorTile);
                int rotation = next == null ? outputDirection.r : conveyorTile.relativeTo(next);
                Util.addBuildPlan(new BuildPlan(conveyorTile.x, conveyorTile.y, rotation, conveyor));
            }
        }
    }

    private static class Bounds {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        void include(Rect rect) {
            minX = Math.min(minX, (int) rect.x);
            minY = Math.min(minY, (int) rect.y);
            maxX = Math.max(maxX, (int) (rect.x + rect.width - 1));
            maxY = Math.max(maxY, (int) (rect.y + rect.height - 1));
        }
    }
}
