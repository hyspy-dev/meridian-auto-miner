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
 * it is core's {@link Worker}. In the proxy's window: the Mine section - what to dig, a shovel for
 * soil, when the drops are picked up, a chest to unload into. On the command channel:
 * {@code mine <conn> box <x y z x y z> | selection | blocks <name> [count] [radius] | cancel}, and
 * {@code mine <conn> set shovel on|off | pickup none|end|every <n> | unload off|nearest|chest <x y z>}.
 */
public class AutoMinerModule implements ProxyModule {

    private static final String USAGE = "box <x y z x y z> | selection | blocks <name> [count] [radius] | cancel"
            + " | set shovel on|off | set pickup none|end|every <n> | set unload off|nearest|chest <x y z>";

    /** When the drops are picked up, as the panel offers it. */
    enum PickUpChoice {
        NONE("Leave them", AutoMiner.PickUp.NONE),
        AT_END("After the clearing", AutoMiner.PickUp.AT_END),
        EVERY("Whenever enough lie about", AutoMiner.PickUp.EVERY);

        private final String label;
        final AutoMiner.PickUp mode;

        PickUpChoice(String label, AutoMiner.PickUp mode) {
            this.label = label;
            this.mode = mode;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Where what was dug goes, as the panel offers it. */
    enum UnloadChoice {
        OFF("Keep it", AutoMiner.Unload.OFF),
        NEAREST("The nearest chest with room", AutoMiner.Unload.NEAREST),
        CHOSEN("The chosen chest", AutoMiner.Unload.CHOSEN);

        private final String label;
        final AutoMiner.Unload mode;

        UnloadChoice(String label, AutoMiner.Unload mode) {
            this.label = label;
            this.mode = mode;
        }

        @Override
        public String toString() {
            return label;
        }
    }

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
    private volatile boolean shovel = true;
    private volatile AutoMiner.PickUp pickUp = AutoMiner.PickUp.AT_END;
    private volatile int every = 20;
    private volatile AutoMiner.Unload unload = AutoMiner.Unload.OFF;
    private volatile meridian.core.api.BlockPos chest;

    @Override
    public void onEnable(ModuleContext ctx) {
        Movement movement = ctx.services().require(Movement.class);
        World world = ctx.services().require(World.class);
        Worker worker = ctx.services().require(Worker.class);
        // The selection tool is the game's own, forced into the toolbar - on 0.6 and later only.
        BuilderSelection selection = ctx.services().get(BuilderSelection.class).orElse(null);
        meridian.core.api.Containers containers = ctx.services().get(meridian.core.api.Containers.class).orElse(null);
        AutoMiner miner = new AutoMiner(movement, world, worker,
                ctx.services().get(meridian.core.api.PlayerState.class).orElse(null),
                ctx.services().get(meridian.core.api.EntityTracker.class).orElse(null), ctx.offloadExecutor(),
                ctx.getLogger());
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
                .bool("mineShovel", "Shovel for dirt, gravel and sand", shovel, v -> shovel = v)
                .enum_("minePickUp", "Pick up the drops", PickUpChoice.class, PickUpChoice.AT_END,
                        v -> pickUp = v.mode)
                .int_("minePickUpEvery", "... whenever this many lie about", 1, 1000, every, v -> every = v)
                .enum_("mineUnload", "Unload what was dug", UnloadChoice.class, UnloadChoice.OFF, v -> unload = v.mode)
                .button("Choose the chest I opened last", () -> {
                    meridian.core.api.BlockPos last = containers == null ? null : containers.lastOpened().orElse(null);
                    if (last == null) {
                        note = "No chest opened yet - open the one to unload into in the game.";
                        return;
                    }
                    chest = last;
                    note = "";
                })
                .liveText("Chest", () -> chest == null ? "None chosen." : chest.x() + " " + chest.y() + " " + chest.z())
                .button("Mine", () -> mine(miner, selection))
                .button("Stop", () -> {
                    note = "";
                    miner.cancel();
                })
                .liveText("Mining", () -> note.isEmpty() ? miner.status() : note);
        ctx.registerSettings(spec.build());
        ctx.getLogger().info("meridian-auto-miner enabled");
    }

    /** How a job goes, as the panel (or the command channel) set it. */
    private AutoMiner.Options options() {
        return new AutoMiner.Options(shovel, pickUp, every, unload, chest);
    }

    /** Mines what the mode says; the Mining line says how it goes. */
    private void mine(AutoMiner miner, BuilderSelection selection) {
        note = "";
        if (unload == AutoMiner.Unload.CHOSEN && chest == null) {
            note = "No chest chosen - open it in the game, then press \"Choose the chest I opened last\".";
            return;
        }
        if (mode == MineMode.BLOCKS) {
            miner.mine(name, count, radius, options());
            return;
        }
        BlockBox box = box(selection);
        if (box == null) {
            note = "No selection - tick the selection tool, then drag a box with it in the game.";
            return;
        }
        miner.mine(box, options());
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

    /** {@code set shovel on|off | set pickup none|end|every <n> | set unload off|nearest|chest <x y z>}. */
    private CompletableFuture<?> set(List<String> args) {
        String what = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "";
        String value = args.size() > 2 ? args.get(2).toLowerCase(Locale.ROOT) : "";
        switch (what) {
            case "shovel" -> shovel = value.equals("on") || value.equals("true");
            case "pickup" -> {
                pickUp = switch (value) {
                    case "none" -> AutoMiner.PickUp.NONE;
                    case "every" -> AutoMiner.PickUp.EVERY;
                    default -> AutoMiner.PickUp.AT_END;
                };
                if (pickUp == AutoMiner.PickUp.EVERY && args.size() > 3) {
                    every = Integer.parseInt(args.get(3));
                }
            }
            case "unload" -> {
                unload = switch (value) {
                    case "nearest" -> AutoMiner.Unload.NEAREST;
                    case "chest" -> AutoMiner.Unload.CHOSEN;
                    default -> AutoMiner.Unload.OFF;
                };
                if (unload == AutoMiner.Unload.CHOSEN) {
                    if (args.size() < 6) {
                        throw new IllegalArgumentException(USAGE);
                    }
                    chest = new meridian.core.api.BlockPos(Integer.parseInt(args.get(3)), Integer.parseInt(args.get(4)),
                            Integer.parseInt(args.get(5)));
                }
            }
            default -> throw new IllegalArgumentException(USAGE);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shovel", shovel);
        out.put("pickup", pickUp.name());
        out.put("every", every);
        out.put("unload", unload.name());
        out.put("chest", chest);
        return CompletableFuture.completedFuture(out);
    }

    /** {@code box <x y z x y z> | selection | blocks <name> [count] [radius] | cancel | set ...}. */
    private CompletableFuture<?> command(AutoMiner miner, BuilderSelection selection, List<String> args) {
        String how = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
        if (how.equals("set")) {
            return set(args);
        }
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
                yield miner.mine(BlockBox.of(v[0], v[1], v[2], v[3], v[4], v[5]), options());
            }
            case "selection" -> {
                BlockBox box = box(selection);
                if (box == null) {
                    throw new IllegalStateException("no selection");
                }
                yield miner.mine(box, options());
            }
            case "blocks" -> {
                if (args.size() < 2) {
                    throw new IllegalArgumentException(USAGE);
                }
                int n = args.size() > 2 ? Integer.parseInt(args.get(2)) : 16;
                double r = args.size() > 3 ? Double.parseDouble(args.get(3)) : 24;
                yield miner.mine(args.get(1), n, r, options());
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
        out.put("unloaded", r.unloaded());
        return out;
    }
}
