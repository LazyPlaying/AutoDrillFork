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
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.production.Drill;

import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;

import static arc.Core.bundle;

public class OptimizationDrill {
    public static void fill(Tile tile, Drill drill) {
        fill(tile, drill, null, true);
    }

    public static void fill(Tile tile, Drill drill, boolean waterExtractorsAndPowerNodes) {
        fill(tile, drill, null, waterExtractorsAndPowerNodes);
    }

    public static void fill(Tile tile, Drill drill, Direction bridgeDirection) {
        fill(tile, drill, bridgeDirection, true);
    }

    public static void fill(Tile tile, Drill drill, Direction bridgeDirection, boolean waterExtractorsAndPowerNodes) {
        boolean bridgeOutputs = drill == Blocks.laserDrill || drill == Blocks.blastDrill;
        int maxTiles = Core.settings.getInt((drill == Blocks.laserDrill ? "laser" : "airblast") + "-drill-max-tiles");

        Seq<Tile> tiles = Util.getConnectedTiles(tile, maxTiles);
        Util.expandArea(tiles, drill.size / 2);

        int minOresPerDrill = Core.settings.getInt((drill == Blocks.blastDrill ? "airblast" : (drill == Blocks.laserDrill ? "laser" : (drill == Blocks.pneumaticDrill ? "pneumatic" : "mechanical"))) + "-drill-min-ores");

        Floor floor = tile.overlay() != Blocks.air ? tile.overlay() : tile.floor();

        ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount = new ObjectMap<>();
        for (Tile t : tiles) {
            tilesItemAndCount.put(t, Util.countOre(t, drill));
        }

        tiles.retainAll(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            return itemAndCount != null && itemAndCount.key == floor.itemDrop && itemAndCount.value >= minOresPerDrill;
        }).sort(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            return itemAndCount == null ? Integer.MIN_VALUE : -itemAndCount.value;
        });

        Seq<Tile> selection = new Seq<>();
        Seq<OutputPlan> outputPlans = new Seq<>();

        int maxTries = Core.settings.getInt(bundle.get("auto-drill.settings.optimization-quality")) * 1000;

        recursiveMaxSearch(tiles, drill, tilesItemAndCount, selection, outputPlans, new Seq<>(), 0, new Seq<>(), maxTries, 0, bridgeOutputs);

        BridgeNetwork bridgeNetwork = null;
        if (bridgeOutputs) {
            bridgeNetwork = planBridgeNetwork(outputPlans, selection, drill, bridgeDirection);
            removeDisconnectedDrills(selection, outputPlans, bridgeNetwork);
            if (selection.isEmpty()) return;

            placeBridgeNetwork(bridgeNetwork);
        } else {
            placeLocalOutputs(outputPlans);
        }

        if (waterExtractorsAndPowerNodes && Core.settings.getBool(bundle.get("auto-drill.settings.place-water-extractor-and-power-nodes")))
            placeWaterExtractorsAndPowerNodes(selection, outputPlans, bridgeNetwork, drill);

        for (Tile t : selection) {
            BuildPlan buildPlan = new BuildPlan(t.x, t.y, 0, drill);
            Vars.player.unit().addBuild(buildPlan);
        }
    }

    private static int recursiveMaxSearch(Seq<Tile> tiles, Drill drill, ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount, Seq<Tile> selection, Seq<OutputPlan> outputPlans, Seq<Rect> rects, int sum, Seq<Integer> triesPerLevel, final int maxTries, final int level, boolean bridgeOutputs) {
        int max = sum;
        Seq<Tile> maxSelection = selection.copy();
        Seq<OutputPlan> maxOutputPlans = outputPlans.copy();

        if (triesPerLevel.size < level + 1) {
            triesPerLevel.setSize(level + 1);
            triesPerLevel.set(level, 0);
        }

        for (Tile tile : tiles) {
            Rect rect = Util.getBlockRect(tile, drill);

            if ((rects.isEmpty() || !overlaps(rects, rect)) && Build.validPlace(drill, Vars.player.team(), tile.x, tile.y, 0)) {
                OutputPlan outputPlan = findOutputPlan(tile, drill, rects, rect, bridgeOutputs);
                if (outputPlan == null) continue;

                int newSum = sum + tilesItemAndCount.get(tile).value;

                Seq<Tile> newSelection = selection.copy().add(tile);
                Seq<OutputPlan> newOutputPlans = outputPlans.copy().add(outputPlan);
                Seq<Rect> newRects = rects.copy().add(rect).add(outputPlan.rect);

                int newMax = recursiveMaxSearch(tiles, drill, tilesItemAndCount, newSelection, newOutputPlans, newRects, newSum, triesPerLevel, maxTries, level + 1, bridgeOutputs);

                if (newMax > max) {
                    max = newMax;
                    maxSelection = newSelection.copy();
                    maxOutputPlans = newOutputPlans.copy();
                }

                triesPerLevel.set(level, triesPerLevel.get(level) + 1);
                if (triesPerLevel.get(level) >= maxTries / Math.pow(2, level + 1)) break;
            }
        }

        selection.clear();
        selection.addAll(maxSelection);
        outputPlans.clear();
        outputPlans.addAll(maxOutputPlans);

        return max;
    }

    private static OutputPlan findOutputPlan(Tile drillTile, Drill drill, Seq<Rect> occupied, Rect drillRect, boolean bridgeOutputs) {
        Seq<Tile> nearby = Util.getNearbyTiles(drillTile.x, drillTile.y, drill);
        nearby.sort(tile -> outputScore(drillTile, tile));

        for (Tile outputTile : nearby) {
            int rotation = outputRotation(drillTile, outputTile);
            Rect outputRect = Util.getBlockRect(outputTile, Blocks.conveyor);
            if (drillRect.overlaps(outputRect) || overlaps(occupied, outputRect)) continue;

            if (bridgeOutputs) {
                BuildPlan bridgePlan = new BuildPlan(outputTile.x, outputTile.y, 0, Blocks.itemBridge);
                if (bridgePlan.placeable(Vars.player.team())) {
                    return new OutputPlan(outputTile, Blocks.itemBridge, 0);
                }
            } else if (isExistingOutput(outputTile)) {
                return new OutputPlan(outputTile, null, rotation);
            } else {
                Block outputBlock = outputBlock(outputTile, rotation);
                if (outputBlock != null) {
                    return new OutputPlan(outputTile, outputBlock, rotation);
                }
            }
        }

        return null;
    }

    private static BridgeNetwork planBridgeNetwork(Seq<OutputPlan> outputPlans, Seq<Tile> selection, Drill drill, Direction bridgeDirection) {
        if (outputPlans.isEmpty()) return new BridgeNetwork(null);

        Seq<Rect> drillRects = new Seq<>();
        for (Tile t : selection) {
            drillRects.add(Util.getBlockRect(t, drill));
        }

        if (bridgeDirection != null) {
            BridgeNetwork network = planBridgeNetwork(outputPlans, drillRects, bridgeDirection);
            return network == null ? new BridgeNetwork(null) : network;
        }

        BridgeNetwork best = null;
        for (Direction direction : Direction.values()) {
            BridgeNetwork network = planBridgeNetwork(outputPlans, drillRects, direction);
            if (network == null || network.connectedOutputs == 0) continue;

            if (best == null || network.connectedOutputs > best.connectedOutputs || (network.connectedOutputs == best.connectedOutputs && network.nodes.size() < best.nodes.size())) {
                best = network;
            }
        }

        return best == null ? new BridgeNetwork(null) : best;
    }

    private static BridgeNetwork planBridgeNetwork(Seq<OutputPlan> outputPlans, Seq<Rect> drillRects, Direction direction) {
        int range = bridgeRange();
        OutputPlan outerMost = outputPlans.max(plan -> direction.primaryAxis(Util.tileToPoint2(plan.tile)));
        if (outerMost == null) return null;

        HashSet<Integer> reserved = new HashSet<>();
        for (OutputPlan outputPlan : outputPlans) {
            reserved.add(outputPlan.tile.pos());
        }

        Tile root = findRootTile(outerMost.tile, direction, drillRects, reserved, range);
        if (root == null) return null;
        reserved.add(root.pos());

        BridgeNetwork network = new BridgeNetwork(root);
        network.add(root, null);

        RouteBounds bounds = new RouteBounds(outputPlans, root, drillRects, range);
        Seq<OutputPlan> sortedOutputs = outputPlans.copy();
        sortedOutputs.sort(plan -> plan.tile.dst2(root));

        for (OutputPlan outputPlan : sortedOutputs) {
            if (network.nodes.containsKey(outputPlan.tile.pos())) {
                network.connectedOutputs++;
                continue;
            }

            Seq<Tile> path = findBridgePath(outputPlan.tile, network, drillRects, reserved, bounds, range);
            if (path == null) continue;

            network.addPath(path);
            network.connectedOutputs++;
            for (Tile tile : path) {
                reserved.add(tile.pos());
            }
        }

        if (network.connectedOutputs == 0) {
            network.nodes.clear();
        }

        return network;
    }

    private static Tile findRootTile(Tile outerMost, Direction direction, Seq<Rect> drillRects, HashSet<Integer> reserved, int range) {
        for (int step = range; step >= 1; step--) {
            Tile root = outerMost.nearby(direction.p.x * step, direction.p.y * step);
            if (canUseBridgeTile(root, drillRects, reserved)) return root;
        }

        return outerMost;
    }

    private static Seq<Tile> findBridgePath(Tile start, BridgeNetwork network, Seq<Rect> drillRects, HashSet<Integer> reserved, RouteBounds bounds, int range) {
        if (network.nodes.containsKey(start.pos())) {
            Seq<Tile> path = new Seq<>();
            path.add(start);
            return path;
        }

        PriorityQueue<PathNode> queue = new PriorityQueue<>((a, b) -> Integer.compare(a.priority, b.priority));
        HashMap<Integer, Integer> bestCosts = new HashMap<>();
        HashSet<Integer> visited = new HashSet<>();

        PathNode first = new PathNode(start, null, 0, estimateBridgeCost(start, network, range));
        queue.add(first);
        bestCosts.put(start.pos(), 0);

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            int currentPos = current.tile.pos();
            if (!visited.add(currentPos)) continue;

            if (network.nodes.containsKey(currentPos)) {
                return current.path();
            }

            for (Direction direction : Direction.values()) {
                for (int distance = range; distance >= 1; distance--) {
                    Tile next = Vars.world.tile(current.tile.x + direction.p.x * distance, current.tile.y + direction.p.y * distance);
                    if (next == null || !bounds.contains(next)) continue;

                    int nextPos = next.pos();
                    if (visited.contains(nextPos) || !canUseBridgeTile(next, drillRects, reserved)) continue;

                    int nextCost = current.cost + bridgeTileCost(next);
                    Integer knownCost = bestCosts.get(nextPos);
                    if (knownCost != null && knownCost <= nextCost) continue;

                    bestCosts.put(nextPos, nextCost);
                    queue.add(new PathNode(next, current, nextCost, nextCost + estimateBridgeCost(next, network, range)));
                }
            }
        }

        return null;
    }

    private static void removeDisconnectedDrills(Seq<Tile> selection, Seq<OutputPlan> outputPlans, BridgeNetwork bridgeNetwork) {
        if (bridgeNetwork == null) return;

        for (int i = outputPlans.size - 1; i >= 0; i--) {
            if (!bridgeNetwork.nodes.containsKey(outputPlans.get(i).tile.pos())) {
                outputPlans.remove(i);
                selection.remove(i);
            }
        }
    }

    private static void placeBridgeNetwork(BridgeNetwork bridgeNetwork) {
        if (bridgeNetwork == null || bridgeNetwork.nodes.isEmpty()) return;

        for (BridgeNode node : bridgeNetwork.nodes.values()) {
            Point2 config = new Point2();
            if (node.next != null) {
                config = new Point2(node.next.x - node.tile.x, node.next.y - node.tile.y);
            }

            BuildPlan buildPlan = new BuildPlan(node.tile.x, node.tile.y, 0, Blocks.itemBridge, config);
            Vars.player.unit().addBuild(buildPlan);
        }
    }

    private static void placeLocalOutputs(Seq<OutputPlan> outputPlans) {
        for (OutputPlan outputPlan : outputPlans) {
            if (outputPlan.block == null) continue;

            BuildPlan buildPlan = new BuildPlan(outputPlan.tile.x, outputPlan.tile.y, outputPlan.rotation, outputPlan.block);
            Vars.player.unit().addBuild(buildPlan);
        }
    }

    private static void placeWaterExtractorsAndPowerNodes(Seq<Tile> selection, Seq<OutputPlan> outputPlans, BridgeNetwork bridgeNetwork, Drill drill) {
        Seq<Rect> rects = new Seq<>();
        for (Tile t : selection) {
            rects.add(Util.getBlockRect(t, drill));
        }

        if (bridgeNetwork != null) {
            for (BridgeNode node : bridgeNetwork.nodes.values()) {
                rects.add(Util.getBlockRect(node.tile, Blocks.itemBridge));
            }
        } else {
            for (OutputPlan outputPlan : outputPlans) {
                rects.add(outputPlan.rect);
            }
        }

        Seq<Tile> waterExtractorTiles = new Seq<>();
        Seq<Tile> powerNodeTiles = new Seq<>();

        for (Tile t : selection) {
            Seq<Tile> nearby = Util.getNearbyTiles(t.x, t.y, drill.size, Blocks.waterExtractor.size);

            for (Tile n : nearby) {
                Rect waterExtractorRect = Util.getBlockRect(n, Blocks.waterExtractor);
                BuildPlan buildPlan = new BuildPlan(n.x, n.y, 0, Blocks.waterExtractor);

                if (buildPlan.placeable(Vars.player.team()) && !overlaps(rects, waterExtractorRect)) {
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

                if (buildPlan.placeable(Vars.player.team()) && !overlaps(rects, powerNodeRect)) {
                    powerNodeTiles.add(n);
                    rects.add(powerNodeRect);
                    break;
                }
            }
        }

        for (Tile waterExtractorTile : waterExtractorTiles) {
            BuildPlan buildPlan = new BuildPlan(waterExtractorTile.x, waterExtractorTile.y, 0, Blocks.waterExtractor);
            Vars.player.unit().addBuild(buildPlan);
        }

        for (Tile powerNodeTile : powerNodeTiles) {
            BuildPlan buildPlan = new BuildPlan(powerNodeTile.x, powerNodeTile.y, 0, Blocks.powerNode);
            Vars.player.unit().addBuild(buildPlan);
        }
    }

    private static float outputScore(Tile drillTile, Tile outputTile) {
        float score = Math.abs(outputTile.x - drillTile.x) + Math.abs(outputTile.y - drillTile.y);
        if (outputTile.drop() != null) score += 100f;
        if (isExistingOutput(outputTile)) score -= 50f;
        return score;
    }

    private static int outputRotation(Tile drillTile, Tile outputTile) {
        int dx = outputTile.x - drillTile.x;
        int dy = outputTile.y - drillTile.y;

        if (Math.abs(dx) > Math.abs(dy)) {
            return dx > 0 ? 0 : 2;
        }

        return dy > 0 ? 1 : 3;
    }

    private static Block outputBlock(Tile tile, int rotation) {
        Block[] outputBlocks = {Blocks.titaniumConveyor, Blocks.conveyor, Blocks.armoredDuct, Blocks.duct};

        for (Block block : outputBlocks) {
            if (block == null || !block.environmentBuildable()) continue;

            BuildPlan buildPlan = new BuildPlan(tile.x, tile.y, rotation, block);
            if (buildPlan.placeable(Vars.player.team())) return block;
        }

        return null;
    }

    private static boolean canUseBridgeTile(Tile tile, Seq<Rect> drillRects, HashSet<Integer> reserved) {
        if (tile == null) return false;

        Rect rect = Util.getBlockRect(tile, Blocks.itemBridge);
        if (overlaps(drillRects, rect)) return false;
        if (reserved.contains(tile.pos())) return true;

        BuildPlan buildPlan = new BuildPlan(tile.x, tile.y, 0, Blocks.itemBridge);
        return buildPlan.placeable(Vars.player.team());
    }

    private static int bridgeTileCost(Tile tile) {
        int cost = 1;
        if (tile.drop() != null) cost += 2;
        if (isExistingOutput(tile)) cost -= 1;
        return cost;
    }

    private static int estimateBridgeCost(Tile tile, BridgeNetwork network, int range) {
        int best = Integer.MAX_VALUE;
        for (BridgeNode target : network.nodes.values()) {
            int distance = Math.abs(tile.x - target.tile.x) + Math.abs(tile.y - target.tile.y);
            best = Math.min(best, Math.max(1, (distance + range - 1) / range));
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    private static int bridgeRange() {
        if (Blocks.itemBridge instanceof ItemBridge) {
            return Math.max(1, ((ItemBridge) Blocks.itemBridge).range);
        }

        return 3;
    }

    private static boolean isExistingOutput(Tile tile) {
        return tile.build != null && tile.build.team == Vars.player.team() && tile.block().acceptsItems;
    }

    private static boolean overlaps(Seq<Rect> rects, Rect rect) {
        return rects.find(r -> r.overlaps(rect)) != null;
    }

    private static class OutputPlan {
        final Tile tile;
        final Block block;
        final int rotation;
        final Rect rect;

        OutputPlan(Tile tile, Block block, int rotation) {
            this.tile = tile;
            this.block = block;
            this.rotation = rotation;
            this.rect = Util.getBlockRect(tile, Blocks.conveyor);
        }
    }

    private static class BridgeNetwork {
        final Tile root;
        final HashMap<Integer, BridgeNode> nodes = new HashMap<>();
        int connectedOutputs;

        BridgeNetwork(Tile root) {
            this.root = root;
        }

        void add(Tile tile, Tile next) {
            BridgeNode existing = nodes.get(tile.pos());
            if (existing == null) {
                nodes.put(tile.pos(), new BridgeNode(tile, next));
            } else if (existing.next == null && (root == null || tile.pos() != root.pos())) {
                existing.next = next;
            }
        }

        void addPath(Seq<Tile> path) {
            for (int i = 0; i < path.size - 1; i++) {
                add(path.get(i), path.get(i + 1));
            }

            if (!path.isEmpty()) {
                add(path.peek(), null);
            }
        }
    }

    private static class BridgeNode {
        final Tile tile;
        Tile next;

        BridgeNode(Tile tile, Tile next) {
            this.tile = tile;
            this.next = next;
        }
    }

    private static class PathNode {
        final Tile tile;
        final PathNode previous;
        final int cost;
        final int priority;

        PathNode(Tile tile, PathNode previous, int cost, int priority) {
            this.tile = tile;
            this.previous = previous;
            this.cost = cost;
            this.priority = priority;
        }

        Seq<Tile> path() {
            Seq<Tile> reversed = new Seq<>();
            PathNode current = this;

            while (current != null) {
                reversed.add(current.tile);
                current = current.previous;
            }

            Seq<Tile> path = new Seq<>();
            for (int i = reversed.size - 1; i >= 0; i--) {
                path.add(reversed.get(i));
            }

            return path;
        }
    }

    private static class RouteBounds {
        final int minX;
        final int minY;
        final int maxX;
        final int maxY;

        RouteBounds(Seq<OutputPlan> outputPlans, Tile root, Seq<Rect> drillRects, int range) {
            int minX = root.x;
            int minY = root.y;
            int maxX = root.x;
            int maxY = root.y;

            for (OutputPlan outputPlan : outputPlans) {
                minX = Math.min(minX, outputPlan.tile.x);
                minY = Math.min(minY, outputPlan.tile.y);
                maxX = Math.max(maxX, outputPlan.tile.x);
                maxY = Math.max(maxY, outputPlan.tile.y);
            }

            for (Rect rect : drillRects) {
                minX = Math.min(minX, (int) rect.x);
                minY = Math.min(minY, (int) rect.y);
                maxX = Math.max(maxX, (int) (rect.x + rect.width));
                maxY = Math.max(maxY, (int) (rect.y + rect.height));
            }

            int margin = range * 6 + 8;
            this.minX = Math.max(0, minX - margin);
            this.minY = Math.max(0, minY - margin);
            this.maxX = Math.min(Vars.world.width() - 1, maxX + margin);
            this.maxY = Math.min(Vars.world.height() - 1, maxY + margin);
        }

        boolean contains(Tile tile) {
            return tile.x >= minX && tile.x <= maxX && tile.y >= minY && tile.y <= maxY;
        }
    }
}
