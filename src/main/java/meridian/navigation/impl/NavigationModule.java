package meridian.navigation.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.core.api.BlockPos;
import meridian.core.api.Movement;
import meridian.navigation.api.Goal;
import meridian.navigation.api.NavResult;
import meridian.navigation.api.Navigator;

/**
 * meridian-navigation: offers the {@link Navigator} service - a way over the ground, walked through
 * core's {@link Movement} - for other modules to take the player somewhere. On the command channel:
 * {@code nav <conn> goto|plan <x y z> [radius]} and {@code nav <conn> cancel}.
 */
public class NavigationModule implements ProxyModule {

    private static final String USAGE = "goto <x y z> [radius] | plan <x y z> [radius] | cancel";

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
        ctx.getLogger().info("meridian-navigation enabled");
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
