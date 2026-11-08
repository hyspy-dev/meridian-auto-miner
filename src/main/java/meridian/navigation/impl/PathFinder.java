package meridian.navigation.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import java.util.Set;
import meridian.core.api.BlockPos;
import meridian.core.api.Movement;
import meridian.core.api.Vec3;
import meridian.navigation.api.Goal;
import meridian.navigation.api.Navigator.Step;

/**
 * A* over the cells the player can stand in ({@link Movement#standAt}), the edges being the moves
 * the body has: a walk (a step up or down included), a jump up, a pull-up onto a ledge, a drop no
 * longer than it survives unhurt. Costs are seconds; the guess is the straight distance at a run.
 *
 * <p>Diagonal edges are walks only, and only where both corners leave room for the body - no
 * cutting past a block. A search has a budget of cells and of time; out of budget, or out of the
 * world core knows, it gives the way to the cell it saw nearest the goal, so the walker can go that
 * far and look again (the way Baritone plans in segments).
 */
final class PathFinder {

    static final int MAX_NODES = 30_000;
    static final long BUDGET_NANOS = 1_500_000_000L;

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** A way: its moves, and whether it reaches the goal (or only gets nearer). */
    record Way(List<Step> steps, boolean complete) {}

    private record Node(int x, int y, int z, double feet, double g, double h, Node parent, Movement.Kind kind) {
        double f() {
            return g + h;
        }
    }

    private final Movement movement;

    PathFinder(Movement movement) {
        this.movement = movement;
    }

    /** The way from standing in {@code (sx, sy, sz)} with the feet at {@code feet} to {@code goal}. */
    Way find(int sx, int sy, int sz, double feet, Goal goal) {
        Movement.Capabilities caps = movement.capabilities();
        Map<Long, OptionalDouble> stands = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Long, Double> best = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        Node start = new Node(sx, sy, sz, feet, 0, guess(sx, sy, sz, goal, caps), null, null);
        open.add(start);
        best.put(key(sx, sy, sz), 0.0);
        Node nearest = start;
        long deadline = System.nanoTime() + BUDGET_NANOS;
        int expanded = 0;
        while (!open.isEmpty()) {
            Node n = open.poll();
            long k = key(n.x(), n.y(), n.z());
            if (!closed.add(k)) {
                continue;
            }
            if (goal.reachedBy(n.x(), n.y(), n.z())) {
                return new Way(steps(n), true);
            }
            if (n.h() < nearest.h()) {
                nearest = n;
            }
            if (++expanded > MAX_NODES || System.nanoTime() > deadline) {
                break;
            }
            for (Node next : neighbours(n, caps, stands, goal)) {
                long nk = key(next.x(), next.y(), next.z());
                if (closed.contains(nk)) {
                    continue;
                }
                Double known = best.get(nk);
                if (known == null || next.g() < known) {
                    best.put(nk, next.g());
                    open.add(next);
                }
            }
        }
        return new Way(steps(nearest), false);
    }

    private List<Node> neighbours(Node n, Movement.Capabilities caps, Map<Long, OptionalDouble> stands, Goal goal) {
        List<Node> out = new ArrayList<>();
        int up = (int) Math.ceil(caps.mantleHeight());
        int down = (int) Math.floor(caps.safeDrop());
        for (int[] d : DIRS) {
            boolean diagonal = d[0] != 0 && d[1] != 0;
            for (int dy = up; dy >= -down; dy--) {
                int x = n.x() + d[0];
                int y = n.y() + dy;
                int z = n.z() + d[1];
                OptionalDouble stand = stands.computeIfAbsent(key(x, y, z), k -> movement.standAt(new BlockPos(x, y, z)));
                if (stand.isEmpty()) {
                    continue;
                }
                double feet = stand.getAsDouble();
                double rise = feet - n.feet();
                Movement.Kind kind;
                double cost;
                double dist = diagonal ? Math.sqrt(2) : 1;
                if (Math.abs(rise) <= caps.stepHeight() + 1e-6) {
                    kind = Movement.Kind.WALK;
                    cost = dist / caps.runSpeed();
                } else if (rise > 0 && rise <= caps.jumpHeight() + 1e-6) {
                    kind = Movement.Kind.JUMP;
                    cost = 0.75;
                } else if (rise > 0 && rise <= caps.mantleHeight() + 1e-6) {
                    kind = Movement.Kind.MANTLE;
                    cost = 1.6;
                } else if (rise < 0 && -rise <= caps.safeDrop()) {
                    kind = Movement.Kind.DROP;
                    cost = 0.3 + Math.sqrt(2 * -rise / 32.0) + 0.3 * -rise / caps.safeDrop();
                } else {
                    continue;
                }
                if (diagonal) {
                    if (kind != Movement.Kind.WALK) {
                        continue;
                    }
                    double top = Math.max(feet, n.feet());
                    // Both corners leave room for the body: no cutting past a block.
                    if (!movement.fits(new Vec3(n.x() + d[0] + 0.5, top, n.z() + 0.5), caps.height())
                            || !movement.fits(new Vec3(n.x() + 0.5, top, n.z() + d[1] + 0.5), caps.height())) {
                        continue;
                    }
                }
                if (kind == Movement.Kind.JUMP || kind == Movement.Kind.MANTLE) {
                    // Room to rise where it stands.
                    if (!movement.fits(new Vec3(n.x() + 0.5, n.feet(), n.z() + 0.5), caps.height() + rise)) {
                        continue;
                    }
                } else if (kind == Movement.Kind.DROP) {
                    // A clear fall over the landing, from the height it steps off at.
                    if (!movement.fits(new Vec3(x + 0.5, feet, z + 0.5), caps.height() - rise)) {
                        continue;
                    }
                }
                out.add(new Node(x, y, z, feet, n.g() + cost, guess(x, y, z, goal, caps), n, kind));
            }
        }
        return out;
    }

    /** The least time left: the straight distance to the goal's edge at a run. */
    private static double guess(int x, int y, int z, Goal goal, Movement.Capabilities caps) {
        double dx = x - goal.x();
        double dy = y - goal.y();
        double dz = z - goal.z();
        double d = Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - goal.radius());
        return d / caps.sprintSpeed();
    }

    private static List<Step> steps(Node last) {
        List<Step> out = new ArrayList<>();
        for (Node n = last; n != null && n.parent() != null; n = n.parent()) {
            out.add(new Step(n.kind(), n.x(), n.y(), n.z(), n.feet()));
        }
        Collections.reverse(out);
        return out;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
