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
import meridian.core.api.Movement;
import meridian.navigation.api.Goal;
import meridian.navigation.api.NavResult;
import meridian.navigation.api.Navigator;

/**
 * meridian-navigation: offers the {@link Navigator} service - a way over the ground, walked through
 * core's {@link Movement} - for other modules to take the player somewhere. A game's player is
 * taken there too: core takes the body from the game for the trip. In the proxy's window: a point
 * to go to, filled from where the player stands or the block they last clicked, Go and Stop. On the
 * command channel: {@code nav <conn> goto|plan <x y z> [radius]} and {@code nav <conn> cancel}.
 */
public class NavigationModule implements ProxyModule {

    private static final String USAGE = "goto <x y z> [radius] | plan <x y z> [radius] | cancel";

    private final SettingBinding<String> xBinding = new SettingBinding<>();
    private final SettingBinding<String> yBinding = new SettingBinding<>();
    private final SettingBinding<String> zBinding = new SettingBinding<>();
    private volatile String xText = "";
    private volatile String yText = "";
    private volatile String zText = "";
    private volatile String status = "Idle.";

    @Override
    public void onEnable(ModuleContext ctx) {
        Movement movement = ctx.services().require(Movement.class);
        // Doors on the way are opened through core's interactions, where it offers them.
        meridian.core.api.InteractionControl interactions =
                ctx.services().get(meridian.core.api.InteractionControl.class).orElse(null);
        meridian.core.api.World world = ctx.services().require(meridian.core.api.World.class);
        Walker walker = new Walker(movement, world, interactions, ctx.offloadExecutor(), ctx.getLogger());
        ctx.services().provide(Navigator.class, walker);
        try {
            ctx.registerCommand("nav", USAGE, (args, json) -> command(walker, args));
        } catch (NoSuchMethodError olderProxy) {
            // no command channel there
        }
        ctx.registerSettings(SettingsSpec.builder()
                .section("Go to a point", SettingsSpec.builder()
                        .string("x", "X", "", v -> xText = v == null ? "" : v, xBinding)
                        .string("y", "Y", "", v -> yText = v == null ? "" : v, yBinding)
                        .string("z", "Z", "", v -> zText = v == null ? "" : v, zBinding)
                        .button("Fill from current position", () -> fill(movement.position()))
                        .button("Fill from the last block I clicked", () -> fillLooked(interactions))
                        .button("Go", () -> go(walker))
                        .button("Stop", () -> {
                            walker.cancel();
                            status = "Stopped.";
                        })
                        .build())
                .liveText("Status", () -> status)
                .build());
        ctx.getLogger().info("meridian-navigation enabled");
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

    private static CompletableFuture<?> command(Walker walker, List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException(USAGE);
        }
        String what = args.get(0).toLowerCase(Locale.ROOT);
        if (what.equals("cancel")) {
            walker.cancel();
            return CompletableFuture.completedFuture(Map.of("cancelled", true));
        }
        if (args.size() < 4) {
            throw new IllegalArgumentException(USAGE);
        }
        BlockPos cell = new BlockPos(Integer.parseInt(args.get(1)), Integer.parseInt(args.get(2)),
                Integer.parseInt(args.get(3)));
        double radius = args.size() > 4 ? Double.parseDouble(args.get(4)) : 0;
        Goal goal = Goal.near(cell, radius);
        return switch (what) {
            case "goto" -> walker.goTo(goal).thenApply(NavigationModule::result);
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
