package meridian.navigation.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.settings.SettingBinding;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.BlockPos;
import meridian.core.api.BuilderSelection;
import meridian.core.api.EntityTracker;
import meridian.core.api.Movement;
import meridian.navigation.api.Goal;
import meridian.navigation.api.NavResult;
import meridian.navigation.api.Navigator;

/**
 * meridian-navigation: offers the {@link Navigator} service - a way over the ground, walked through
 * core's {@link Movement} - for other modules to take the player somewhere. A game's player is
 * taken there too: core takes the body from the game for the trip. In the proxy's window: a point
 * to go to, filled from where the player stands or the block they last clicked, Go and Stop; and
 * mining ({@link Miner}) - the game's selection tool put in the first slot, the box it outlines dug
 * out, or the blocks of a kind around the player. On the command channel:
 * {@code nav <conn> goto|plan <x y z> [radius]}, {@code nav <conn> mine box <x y z x y z> | selection |
 * blocks <name> [count] [radius]} and {@code nav <conn> cancel}.
 */
public class NavigationModule implements ProxyModule {

    private static final String USAGE = "goto <x y z> [radius] | plan <x y z> [radius] | mine box <x y z x y z> "
            + "| mine selection | mine blocks <name> [count] [radius] | cancel";

    /** What the Mine button digs. */
    enum MineMode {
        SELECTION("The selection"),
        BLOCKS("Blocks by name");

        private final String label;

        MineMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final SettingBinding<String> xBinding = new SettingBinding<>();
    private final SettingBinding<String> yBinding = new SettingBinding<>();
    private final SettingBinding<String> zBinding = new SettingBinding<>();
    private volatile String xText = "";
    private volatile String yText = "";
    private volatile String zText = "";
    private volatile String status = "Idle.";
    private volatile MineMode mineMode = MineMode.SELECTION;
    private volatile String mineName = "Ore_Copper";
    private volatile int mineCount = 16;
    private volatile int mineRadius = 24;

    @Override
    public void onEnable(ModuleContext ctx) {
        Movement movement = ctx.services().require(Movement.class);
        // Doors on the way are opened through core's interactions, where it offers them.
        meridian.core.api.InteractionControl interactions =
                ctx.services().get(meridian.core.api.InteractionControl.class).orElse(null);
        meridian.core.api.World world = ctx.services().require(meridian.core.api.World.class);
        Walker walker = new Walker(movement, world, interactions, ctx.offloadExecutor(), ctx.getLogger());
        ctx.services().provide(Navigator.class, walker);
        // The selection tool is the game's own, forced into the toolbar - on 0.6 and later only.
        BuilderSelection selection = ctx.services().get(BuilderSelection.class).orElse(null);
        EntityTracker entities = ctx.services().get(EntityTracker.class).orElse(null);
        Miner miner = interactions == null ? null
                : new Miner(movement, world, interactions, entities, walker, ctx.offloadExecutor(), ctx.getLogger());
        try {
            ctx.registerCommand("nav", USAGE, (args, json) -> command(walker, miner, selection, args));
        } catch (NoSuchMethodError olderProxy) {
            // no command channel there
        }
        SettingsSpec.Builder mine = SettingsSpec.builder();
        if (selection != null) {
            mine.bool("selectionTool", "Selection tool in slot 1", false, on -> selection.forceTools(
                            on ? List.of(BuilderSelection.SELECTION_TOOL) : List.of()))
                    .liveText("Selection", () -> describe(selection.current().orElse(null)));
        }
        mine.enum_("mineMode", "What to mine", MineMode.class, MineMode.SELECTION, v -> mineMode = v)
                .string("mineName", "Blocks (a part of the name)", mineName, v -> mineName = v == null ? "" : v.trim())
                .int_("mineCount", "How many blocks", 1, 1000, mineCount, v -> mineCount = v)
                .int_("mineRadius", "Within (blocks of here)", 1, 64, mineRadius, v -> mineRadius = v)
                .button("Mine", () -> mine(miner, selection))
                .button("Stop", () -> stop(walker, miner))
                .liveText("Mining", () -> miner == null ? "Breaking blocks is not offered here." : miner.status());
        ctx.registerSettings(SettingsSpec.builder()
                .section("Go to a point", SettingsSpec.builder()
                        .string("x", "X", "", v -> xText = v == null ? "" : v, xBinding)
                        .string("y", "Y", "", v -> yText = v == null ? "" : v, yBinding)
                        .string("z", "Z", "", v -> zText = v == null ? "" : v, zBinding)
                        .button("Fill from current position", () -> fill(movement.position()))
                        .button("Fill from the last block I clicked", () -> fillLooked(interactions))
                        .button("Go", () -> afterMining(miner).thenRun(() -> go(walker)))
                        .button("Stop", () -> stop(walker, miner))
                        .build())
                .liveText("Status", () -> status)
                .section("Mine", mine.build())
                .build());
        ctx.getLogger().info("meridian-navigation enabled");
    }

    /** Stops the trip and the mining under way. */
    private void stop(Walker walker, Miner miner) {
        if (miner != null) {
            miner.cancel();
        }
        walker.cancel();
        status = "Stopped.";
    }

    /** Done at once with no mining under way; else once the mining has stopped and given the body back. */
    private static CompletableFuture<Void> afterMining(Miner miner) {
        return miner == null || !miner.busy() ? CompletableFuture.completedFuture(null) : miner.cancel();
    }

    /** Mines what the mode says; the Mining line says how it goes. */
    private void mine(Miner miner, BuilderSelection selection) {
        if (miner == null) {
            return;
        }
        if (mineMode == MineMode.BLOCKS) {
            miner.mine(mineName, mineCount, mineRadius);
            return;
        }
        BuilderSelection.Box box = selection == null ? null : selection.current().orElse(null);
        if (box == null) {
            status = "No selection - tick the selection tool, then drag a box with it in the game.";
            return;
        }
        miner.mine(Miner.Box.of(box.xMin(), box.yMin(), box.zMin(), box.xMax(), box.yMax(), box.zMax()));
    }

    private static String describe(BuilderSelection.Box b) {
        if (b == null) {
            return "None - drag a box with the selection tool.";
        }
        long n = (long) (b.xMax() - b.xMin() + 1) * (b.yMax() - b.yMin() + 1) * (b.zMax() - b.zMin() + 1);
        return b.xMin() + " " + b.yMin() + " " + b.zMin() + " to " + b.xMax() + " " + b.yMax() + " " + b.zMax() + " ("
                + n + " blocks)";
    }

    /** Fills the point with where the feet are, as the cell they stand in. */
    private void fill(meridian.core.api.Vec3 at) {
        if (at == null) {
            status = "No position yet - join a world and move once.";
            return;
        }
        int[] c = Walker.cellOf(at);
        set(c[0], c[1], c[2]);
    }

    /** Fills the point with the cell over the block the player last clicked - where they would stand on it. */
    private void fillLooked(meridian.core.api.InteractionControl interactions) {
        BlockPos b = interactions == null ? null : interactions.targetedBlock().orElse(null);
        if (b == null) {
            status = "No block clicked yet - click one in the game.";
            return;
        }
        set(b.x(), b.y() + 1, b.z());
    }

    private void set(int x, int y, int z) {
        xText = Integer.toString(x);
        yText = Integer.toString(y);
        zText = Integer.toString(z);
        xBinding.set(xText);
        yBinding.set(yText);
        zBinding.set(zText);
    }

    /** Goes to the point in the fields; the status says how it went. */
    private void go(Walker walker) {
        BlockPos cell;
        try {
            cell = new BlockPos(Integer.parseInt(xText.trim()), Integer.parseInt(yText.trim()),
                    Integer.parseInt(zText.trim()));
        } catch (NumberFormatException e) {
            status = "X, Y and Z must be whole numbers.";
            return;
        }
        if (!walker.available()) {
            status = "Cannot move the player yet - join a world and move once.";
            return;
        }
        status = "Going to " + cell.x() + " " + cell.y() + " " + cell.z() + "...";
        walker.goTo(Goal.near(cell, 0)).whenComplete((r, e) -> {
            if (e != null || r == null) {
                status = "Failed: " + (e == null ? "no result" : e.getMessage());
                return;
            }
            String where = r.at() == null ? "" : String.format(Locale.ROOT, " at %.1f %.1f %.1f",
                    r.at().x(), r.at().y(), r.at().z());
            status = switch (r.status()) {
                case ARRIVED -> "Arrived" + where + " (" + r.moves() + " moves).";
                case NO_PATH -> "No way there" + where + ".";
                case BLOCKED -> "Blocked on the way" + where + ".";
                case INTERRUPTED -> "Stopped" + where + ".";
                case NOT_AVAILABLE -> "Cannot move the player here.";
            };
        });
    }

    private static CompletableFuture<?> command(Walker walker, Miner miner, BuilderSelection selection,
                                                List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException(USAGE);
        }
        String what = args.get(0).toLowerCase(Locale.ROOT);
        if (what.equals("cancel")) {
            if (miner != null) {
                miner.cancel();
            }
            walker.cancel();
            return CompletableFuture.completedFuture(Map.of("cancelled", true));
        }
        if (what.equals("mine")) {
            return mineCommand(miner, selection, args).thenApply(NavigationModule::result);
        }
        if (args.size() < 4) {
            throw new IllegalArgumentException(USAGE);
        }
        BlockPos cell = new BlockPos(Integer.parseInt(args.get(1)), Integer.parseInt(args.get(2)),
                Integer.parseInt(args.get(3)));
        double radius = args.size() > 4 ? Double.parseDouble(args.get(4)) : 0;
        Goal goal = Goal.near(cell, radius);
        return switch (what) {
            case "goto" -> afterMining(miner).thenCompose(v -> walker.goTo(goal)).thenApply(NavigationModule::result);
            case "plan" -> CompletableFuture.supplyAsync(() -> {
                List<Navigator.Step> steps = walker.plan(goal);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("moves", steps.size());
                out.put("steps", steps.stream().map(s -> s.kind() + " " + s.x() + " " + s.y() + " " + s.z() + " " + s.gait()).toList());
                return out;
            });
            default -> throw new IllegalArgumentException(USAGE);
        };
    }

    /** {@code mine box <x y z x y z> | mine selection | mine blocks <name> [count] [radius]}. */
    private static CompletableFuture<Miner.Result> mineCommand(Miner miner, BuilderSelection selection,
                                                               List<String> args) {
        if (miner == null) {
            throw new IllegalStateException("breaking blocks is not offered here");
        }
        String how = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "";
        switch (how) {
            case "box" -> {
                if (args.size() < 8) {
                    throw new IllegalArgumentException(USAGE);
                }
                int[] v = new int[6];
                for (int i = 0; i < 6; i++) {
                    v[i] = Integer.parseInt(args.get(2 + i));
                }
                return miner.mine(Miner.Box.of(v[0], v[1], v[2], v[3], v[4], v[5]));
            }
            case "selection" -> {
                BuilderSelection.Box b = selection == null ? null : selection.current().orElse(null);
                if (b == null) {
                    throw new IllegalStateException("no selection");
                }
                return miner.mine(Miner.Box.of(b.xMin(), b.yMin(), b.zMin(), b.xMax(), b.yMax(), b.zMax()));
            }
            case "blocks" -> {
                if (args.size() < 3) {
                    throw new IllegalArgumentException(USAGE);
                }
                int count = args.size() > 3 ? Integer.parseInt(args.get(3)) : 16;
                double radius = args.size() > 4 ? Double.parseDouble(args.get(4)) : 24;
                return miner.mine(args.get(2), count, radius);
            }
            default -> throw new IllegalArgumentException(USAGE);
        }
    }

    private static Map<String, Object> result(Miner.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status().name());
        out.put("broken", r.broken());
        out.put("dug", r.dug());
        out.put("skipped", r.skipped());
        out.put("picked", r.picked());
        out.put("left", r.left());
        return out;
    }

    private static Map<String, Object> result(NavResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status().name());
        if (r.at() != null) {
            out.put("at", Map.of("x", r.at().x(), "y", r.at().y(), "z", r.at().z()));
        }
        out.put("moves", r.moves());
        out.put("replans", r.replans());
        return out;
    }
}
