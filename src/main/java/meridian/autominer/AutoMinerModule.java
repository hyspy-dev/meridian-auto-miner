package meridian.autominer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.BlockBox;
import meridian.core.api.BuilderSelection;
import meridian.core.api.Movement;
import meridian.core.api.Worker;
import meridian.core.api.World;

/**
 * meridian-auto-miner: digs, with the player's own character or a headless one - the box dragged
 * with the game's selection tool (put in the first slot from here), or the blocks of a kind around
 * the player. What to dig is decided here ({@link AutoMiner}); getting at each block and breaking
 * it is core's {@link Worker}. In the proxy's window: the Mine section. On the command channel:
 * {@code mine <conn> box <x y z x y z> | selection | blocks <name> [count] [radius] | cancel}.
 */
public class AutoMinerModule implements ProxyModule {

    private static final String USAGE = "box <x y z x y z> | selection | blocks <name> [count] [radius] | cancel";

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

    private volatile MineMode mode = MineMode.SELECTION;
    private volatile String name = "Ore_Copper";
    private volatile int count = 16;
    private volatile int radius = 24;
    private volatile String note = "";

    @Override
    public void onEnable(ModuleContext ctx) {
        Movement movement = ctx.services().require(Movement.class);
        World world = ctx.services().require(World.class);
        Worker worker = ctx.services().require(Worker.class);
        // The selection tool is the game's own, forced into the toolbar - on 0.6 and later only.
        BuilderSelection selection = ctx.services().get(BuilderSelection.class).orElse(null);
        AutoMiner miner = new AutoMiner(movement, world, worker, ctx.offloadExecutor(), ctx.getLogger());
        try {
            ctx.registerCommand("mine", USAGE, (args, json) -> command(miner, selection, args));
        } catch (NoSuchMethodError olderProxy) {
            // no command channel there
        }
        SettingsSpec.Builder spec = SettingsSpec.builder();
        if (selection != null) {
            spec.bool("selectionTool", "Selection tool in slot 1", false, on -> selection.forceTools(
                            on ? List.of(BuilderSelection.SELECTION_TOOL) : List.of()))
                    .liveText("Selection", () -> describe(selection.current().orElse(null)));
        }
        spec.enum_("mineMode", "What to mine", MineMode.class, MineMode.SELECTION, v -> mode = v)
                .string("mineName", "Blocks (a part of the name)", name, v -> name = v == null ? "" : v.trim())
                .int_("mineCount", "How many blocks", 1, 1000, count, v -> count = v)
                .int_("mineRadius", "Within (blocks of here)", 1, 64, radius, v -> radius = v)
                .button("Mine", () -> mine(miner, selection))
                .button("Stop", () -> {
                    note = "";
                    miner.cancel();
                })
                .liveText("Mining", () -> note.isEmpty() ? miner.status() : note);
        ctx.registerSettings(spec.build());
        ctx.getLogger().info("meridian-auto-miner enabled");
    }

    /** Mines what the mode says; the Mining line says how it goes. */
    private void mine(AutoMiner miner, BuilderSelection selection) {
        note = "";
        if (mode == MineMode.BLOCKS) {
            miner.mine(name, count, radius);
            return;
        }
        BlockBox box = box(selection);
        if (box == null) {
            note = "No selection - tick the selection tool, then drag a box with it in the game.";
            return;
        }
        miner.mine(box);
    }

    private static BlockBox box(BuilderSelection selection) {
        BuilderSelection.Box b = selection == null ? null : selection.current().orElse(null);
        return b == null ? null : BlockBox.of(b.xMin(), b.yMin(), b.zMin(), b.xMax(), b.yMax(), b.zMax());
    }

    private static String describe(BuilderSelection.Box b) {
        if (b == null) {
            return "None - drag a box with the selection tool.";
        }
        long n = (long) (b.xMax() - b.xMin() + 1) * (b.yMax() - b.yMin() + 1) * (b.zMax() - b.zMin() + 1);
        return b.xMin() + " " + b.yMin() + " " + b.zMin() + " to " + b.xMax() + " " + b.yMax() + " " + b.zMax() + " ("
                + n + " blocks)";
    }

    /** {@code box <x y z x y z> | selection | blocks <name> [count] [radius] | cancel}. */
    private static CompletableFuture<?> command(AutoMiner miner, BuilderSelection selection, List<String> args) {
        String how = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
        CompletableFuture<AutoMiner.Result> run = switch (how) {
            case "cancel" -> {
                miner.cancel();
                yield null;
            }
            case "box" -> {
                if (args.size() < 7) {
                    throw new IllegalArgumentException(USAGE);
                }
                int[] v = new int[6];
                for (int i = 0; i < 6; i++) {
                    v[i] = Integer.parseInt(args.get(1 + i));
                }
                yield miner.mine(BlockBox.of(v[0], v[1], v[2], v[3], v[4], v[5]));
            }
            case "selection" -> {
                BlockBox box = box(selection);
                if (box == null) {
                    throw new IllegalStateException("no selection");
                }
                yield miner.mine(box);
            }
            case "blocks" -> {
                if (args.size() < 2) {
                    throw new IllegalArgumentException(USAGE);
                }
                int n = args.size() > 2 ? Integer.parseInt(args.get(2)) : 16;
                double r = args.size() > 3 ? Double.parseDouble(args.get(3)) : 24;
                yield miner.mine(args.get(1), n, r);
            }
            default -> throw new IllegalArgumentException(USAGE);
        };
        if (run == null) {
            return CompletableFuture.completedFuture(Map.of("cancelled", true));
        }
        return run.thenApply(AutoMinerModule::result);
    }

    private static Map<String, Object> result(AutoMiner.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status().name());
        out.put("broken", r.broken());
        out.put("dug", r.dug());
        out.put("skipped", r.skipped());
        out.put("picked", r.picked());
        out.put("left", r.left());
        return out;
    }
}
