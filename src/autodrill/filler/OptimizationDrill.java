package autodrill.filler;

import arc.Core;
import arc.math.geom.Point2;
import arc.math.geom.Rect;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.type.Item;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;

import java.util.HashMap;

public class OptimizationDrill {
    private static final int bridgeRange = 4;
    private static final int outletPadding = 8;

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

        Seq<Tile> cleanTiles = tiles.select(t -> !coversForeignResource(t, drill, sourceItem));
        if (!cleanTiles.isEmpty()) {
            tiles = cleanTiles;
        }

        tiles.sort(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            return itemAndCount == null ? Integer.MIN_VALUE : -itemAndCount.value;
        });

        int maxTries = Core.settings.getInt(Util.optimizationQualitySetting, 2) * 1000;
        DrillSelection drillSelection = outputDirection == null ?
                selectOptimizedDrills(tiles, drill, tilesItemAndCount, maxTries) :
                selectOutputAwareDrills(tiles, drill, tilesItemAndCount, sourceItem, outputDirection, maxTries);

        Seq<Tile> selection = drillSelection.tiles;
        Seq<Rect> reservedRects = getDrillRects(selection, drill);

        OutputPlan outputPlan = null;
        if (outputDirection != null) {
            outputPlan = buildBridgeOutput(selection, drill, sourceItem, outputDirection, drillSelection.laneOffset, reservedRects);
            if (outputPlan != null) {
                reservedRects.add(outputPlan.rects);
            }
        }

        if (waterExtractorsAndPowerNodes && Core.settings.getBool(Util.placeWaterExtractorsAndPowerNodesSetting, true))
            placeWaterExtractorsAndPowerNodes(selection, drill, reservedRects, sourceItem);

        if (outputPlan != null) {
            outputPlan.place();
        }

        for (Tile t : selection) {
            BuildPlan buildPlan = new BuildPlan(t.x, t.y, 0, drill);
            Util.addBuildPlan(buildPlan);
        }
    }

    private static DrillSelection selectOptimizedDrills(Seq<Tile> tiles, Drill drill, ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount, int maxTries) {
        Seq<Tile> selection = new Seq<>();
        recursiveMaxSearch(tiles, drill, tilesItemAndCount, selection, new Seq<>(), 0, new Seq<>(), maxTries, 0, 0);
        return new DrillSelection(selection, 0);
    }

    private static DrillSelection selectOutputAwareDrills(Seq<Tile> tiles, Drill drill, ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount, Item sourceItem, Direction outputDirection, int maxTries) {
        int pitch = lanePitch(drill);
        int bestScore = -1;
        DrillSelection best = new DrillSelection(new Seq<>(), 0);

        for (int laneOffset = 0; laneOffset < pitch; laneOffset++) {
            final int offset = laneOffset;
            Seq<Tile> laneTiles = tiles.select(t -> {
                Tile pickup = pickupTile(t, drill, outputDirection, offset);
                return pickup != null && canPlaceOutputBlock(pickup, Blocks.itemBridge, getDrillRects(new Seq<Tile>().add(t), drill), sourceItem);
            });

            if (laneTiles.isEmpty()) continue;

            DrillSelection candidate = selectOptimizedDrills(laneTiles, drill, tilesItemAndCount, maxTries);
            int score = score(candidate.tiles, tilesItemAndCount);
            if (score > bestScore) {
                bestScore = score;
                best = new DrillSelection(candidate.tiles.copy(), laneOffset);
            }
        }

        return best.tiles.isEmpty() ? selectOptimizedDrills(tiles, drill, tilesItemAndCount, maxTries) : best;
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

    private static OutputPlan buildBridgeOutput(Seq<Tile> selection, Drill drill, Item sourceItem, Direction outputDirection, int laneOffset, Seq<Rect> reservedRects) {
        if (selection.isEmpty() || !Blocks.itemBridge.environmentBuildable()) return null;

        OutputPlan plan = new OutputPlan();
        HashMap<Integer, Seq<Tile>> pickupsByLane = new HashMap<>();

        for (Tile drillTile : selection) {
            Tile pickup = pickupTile(drillTile, drill, outputDirection, laneOffset);
            if (!canPlaceOutputBlock(pickup, Blocks.itemBridge, reservedRects, sourceItem)) continue;

            int lane = outputDirection.p.x == 0 ? pickup.x : pickup.y;
            pickupsByLane.computeIfAbsent(lane, ignored -> new Seq<>()).add(pickup);
        }

        for (Seq<Tile> pickups : pickupsByLane.values()) {
            pickups.sort(t -> outputDirection.primaryAxis(Util.tileToPoint2(t)));
            if (outputDirection.p.x < 0 || outputDirection.p.y < 0) {
                pickups.reverse();
            }

            Seq<Tile> laneNodes = new Seq<>();
            for (Tile pickup : pickups) {
                appendBridgePath(laneNodes, pickup, outputDirection, sourceItem, reservedRects);
            }

            Tile outlet = findOutlet(laneNodes.peek(), outputDirection, sourceItem, reservedRects);
            if (outlet != null) {
                appendBridgePath(laneNodes, outlet, outputDirection, sourceItem, reservedRects);
            }

            for (int i = 0; i < laneNodes.size; i++) {
                Tile current = laneNodes.get(i);
                Tile next = i + 1 < laneNodes.size ? laneNodes.get(i + 1) : null;
                Point2 config = next == null ? new Point2() : new Point2(next.x - current.x, next.y - current.y);
                plan.add(new BuildPlan(current.x, current.y, 0, Blocks.itemBridge, config), Blocks.itemBridge);
            }

            if (!laneNodes.isEmpty()) {
                Tile last = laneNodes.peek();
                Tile output = last.nearby(outputDirection.p);
                if (Util.placeable(output, outputConveyor(), outputDirection.r)) {
                    plan.add(new BuildPlan(output.x, output.y, outputDirection.r, outputConveyor()), outputConveyor());
                }
            }
        }

        return plan.plans.isEmpty() ? null : plan;
    }

    private static void appendBridgePath(Seq<Tile> laneNodes, Tile target, Direction outputDirection, Item sourceItem, Seq<Rect> reservedRects) {
        if (target == null) return;
        if (laneNodes.isEmpty()) {
            laneNodes.add(target);
            return;
        }

        Tile current = laneNodes.peek();
        while (bridgeDistance(current, target) > bridgeRange) {
            Tile next = nextBridgeStep(current, target, outputDirection, sourceItem, reservedRects);
            if (next == null || next == current) return;

            laneNodes.add(next);
            current = next;
        }

        if (!laneNodes.contains(target)) {
            laneNodes.add(target);
        }
    }

    private static Tile nextBridgeStep(Tile current, Tile target, Direction outputDirection, Item sourceItem, Seq<Rect> reservedRects) {
        int remaining = Math.abs(target.x - current.x) + Math.abs(target.y - current.y);
        int step = Math.min(bridgeRange, remaining);

        for (int distance = step; distance >= 1; distance--) {
            int dx = outputDirection.p.x * distance;
            int dy = outputDirection.p.y * distance;
            Tile candidate = current.nearby(dx, dy);
            if (candidate == null) continue;
            if ((candidate.x - current.x) * outputDirection.p.x + (candidate.y - current.y) * outputDirection.p.y <= 0) continue;
            if (canPlaceOutputBlock(candidate, Blocks.itemBridge, reservedRects, sourceItem)) return candidate;
        }

        return null;
    }

    private static Tile findOutlet(Tile lastPickup, Direction outputDirection, Item sourceItem, Seq<Rect> reservedRects) {
        if (lastPickup == null) return null;

        Tile current = lastPickup;
        for (int i = 0; i < outletPadding; i++) {
            Tile candidate = current.nearby(outputDirection.p);
            if (candidate == null) return null;

            if (canPlaceOutputBlock(candidate, Blocks.itemBridge, reservedRects, sourceItem)) {
                current = candidate;
                continue;
            }

            break;
        }

        return current == lastPickup ? null : current;
    }

    private static Tile pickupTile(Tile drillTile, Drill drill, Direction outputDirection, int laneOffset) {
        Rect rect = Util.getBlockRect(drillTile, drill);
        int pitch = lanePitch(drill);

        if (outputDirection.p.x != 0) {
            int lower = (int) rect.y - 1;
            int upper = (int) (rect.y + rect.height);
            int y = mod(lower - laneOffset, pitch) == 0 ? lower : (mod(upper - laneOffset, pitch) == 0 ? upper : Integer.MIN_VALUE);
            return y == Integer.MIN_VALUE ? null : Vars.world.tile(drillTile.x, y);
        } else {
            int lower = (int) rect.x - 1;
            int upper = (int) (rect.x + rect.width);
            int x = mod(lower - laneOffset, pitch) == 0 ? lower : (mod(upper - laneOffset, pitch) == 0 ? upper : Integer.MIN_VALUE);
            return x == Integer.MIN_VALUE ? null : Vars.world.tile(x, drillTile.y);
        }
    }

    private static boolean canPlaceOutputBlock(Tile tile, mindustry.world.Block block, Seq<Rect> reservedRects, Item sourceItem) {
        if (tile == null) return false;

        Rect rect = Util.getBlockRect(tile, block);
        if (reservedRects.find(r -> r.overlaps(rect)) != null) return false;
        if (hasForeignOre(rect, sourceItem)) return false;

        return new BuildPlan(tile.x, tile.y, 0, block).placeable(Vars.player.team());
    }

    private static mindustry.world.Block outputConveyor() {
        return Blocks.titaniumConveyor.environmentBuildable() ? Blocks.titaniumConveyor : Blocks.conveyor;
    }

    private static int lanePitch(Drill drill) {
        return drill == Blocks.blastDrill ? drill.size + Blocks.waterExtractor.size : drill.size + 1;
    }

    private static int bridgeDistance(Tile a, Tile b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    private static int mod(int value, int divisor) {
        int result = value % divisor;
        return result < 0 ? result + divisor : result;
    }

    private static int score(Seq<Tile> selection, ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount) {
        int score = 0;
        for (Tile tile : selection) {
            ObjectIntMap.Entry<Item> entry = tilesItemAndCount.get(tile);
            if (entry != null) score += entry.value;
        }
        return score;
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

    private static class DrillSelection {
        final Seq<Tile> tiles;
        final int laneOffset;

        DrillSelection(Seq<Tile> tiles, int laneOffset) {
            this.tiles = tiles;
            this.laneOffset = laneOffset;
        }
    }

    private static class OutputPlan {
        final Seq<BuildPlan> plans = new Seq<>();
        final Seq<Rect> rects = new Seq<>();

        void add(BuildPlan plan, mindustry.world.Block block) {
            if (plan == null) return;

            Rect rect = Util.getBlockRect(Vars.world.tile(plan.x, plan.y), block);
            if (rects.find(r -> r.overlaps(rect)) == null) {
                rects.add(rect);
            }
            plans.add(plan);
        }

        void place() {
            for (BuildPlan plan : plans) {
                Util.addBuildPlan(plan);
            }
        }
    }
}
