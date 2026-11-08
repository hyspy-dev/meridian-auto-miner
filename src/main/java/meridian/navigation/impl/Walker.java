package meridian.navigation.impl;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import meridian.core.api.Movement;
import meridian.core.api.Vec3;
import meridian.navigation.api.Goal;
import meridian.navigation.api.NavResult;
import meridian.navigation.api.Navigator;
import org.slf4j.Logger;

/**
 * The {@link Navigator}: finds a way ({@link PathFinder}) from where the player stands and plays
 * it move by move through {@link Movement}. A blocked move, or a way that only got nearer (the world
 * beyond not sent yet), means looking again from where the feet are now - until the goal, no way,
 * or too many tries.
 */
final class Walker implements Navigator {

    /** Looks again at most this often in one trip. */
    static final int MAX_REPLANS = 12;

    private final Movement movement;
    private final PathFinder finder;
    private final Executor executor;
    private final Logger log;
    /** The trip under way; a new one, or a cancel, moves it on. */
    private final AtomicInteger trip = new AtomicInteger();

    Walker(Movement movement, Executor executor, Logger log) {
        this.movement = movement;
        this.finder = new PathFinder(movement);
        this.executor = executor;
        this.log = log;
    }

    @Override
    public boolean available() {
        return movement.available();
    }

    @Override
    public List<Step> plan(Goal goal) {
        Vec3 p = movement.position();
        if (p == null) {
            return List.of();
        }
        int[] c = cellOf(p);
        return finder.find(c[0], c[1], c[2], p.y(), goal).steps();
    }

    @Override
    public void cancel() {
        trip.incrementAndGet();
        movement.stop();
    }

    @Override
    public CompletableFuture<NavResult> goTo(Goal goal) {
        int id = trip.incrementAndGet();
        movement.stop();
        CompletableFuture<NavResult> done = new CompletableFuture<>();
        if (!movement.available()) {
            done.complete(new NavResult(NavResult.Status.NOT_AVAILABLE, movement.position(), 0, 0));
            return done;
        }
        executor.execute(() -> leg(id, goal, done, 0, 0, Double.MAX_VALUE));
        return done;
    }

    /** One leg: find a way from here, then play it. */
    private void leg(int id, Goal goal, CompletableFuture<NavResult> done, int moves, int replans, double lastGap) {
        if (trip.get() != id) {
            done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
            return;
        }
        Vec3 p = movement.position();
        if (p == null) {
            done.complete(new NavResult(NavResult.Status.NOT_AVAILABLE, null, moves, replans));
            return;
        }
        int[] c = cellOf(p);
        if (goal.reachedBy(c[0], c[1], c[2])) {
            done.complete(new NavResult(NavResult.Status.ARRIVED, p, moves, replans));
            return;
        }
        PathFinder.Way way = finder.find(c[0], c[1], c[2], p.y(), goal);
        double gap = gap(way, c, goal);
        if (way.steps().isEmpty() || (!way.complete() && gap >= lastGap)) {
            log.info("navigation: no way from {} to {} ({} move(s), {} replan(s))", p, goal, moves, replans);
            done.complete(new NavResult(NavResult.Status.NO_PATH, p, moves, replans));
            return;
        }
        log.info("navigation: {} move(s) towards {}{}", way.steps().size(), goal, way.complete() ? "" : " (as far as known)");
        play(id, goal, done, way, 0, moves, replans, gap);
    }

    private void play(int id, Goal goal, CompletableFuture<NavResult> done, PathFinder.Way way, int i,
                      int moves, int replans, double gap) {
        if (trip.get() != id) {
            done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
            return;
        }
        if (i >= way.steps().size()) {
            // Played to its end: there, or as near as was known - look again from here.
            leg(id, goal, done, moves, way.complete() ? replans : replans + 1, way.complete() ? Double.MAX_VALUE : gap);
            return;
        }
        Step s = way.steps().get(i);
        Movement.Move move = new Movement.Move(s.kind(), new Vec3(s.x() + 0.5, s.feet(), s.z() + 0.5), Movement.Gait.RUN);
        movement.move(move).whenCompleteAsync((r, error) -> {
            if (error != null || r == null) {
                done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
            } else if (r.arrived()) {
                play(id, goal, done, way, i + 1, moves + 1, replans, gap);
            } else if (r.status() == Movement.MoveResult.Status.BLOCKED && replans < MAX_REPLANS) {
                log.info("navigation: {} onto ({}, {}, {}) blocked - looking again", s.kind(), s.x(), s.y(), s.z());
                leg(id, goal, done, moves, replans + 1, Double.MAX_VALUE);
            } else {
                NavResult.Status status = r.status() == Movement.MoveResult.Status.BLOCKED
                        ? NavResult.Status.BLOCKED
                        : r.status() == Movement.MoveResult.Status.NOT_AVAILABLE
                            ? NavResult.Status.NOT_AVAILABLE : NavResult.Status.INTERRUPTED;
                done.complete(new NavResult(status, r.at(), moves, replans));
            }
        }, executor);
    }

    /** How far the end of a way is from the goal - a way that only gets nearer has to get nearer each time. */
    private static double gap(PathFinder.Way way, int[] from, Goal goal) {
        int x = from[0];
        int y = from[1];
        int z = from[2];
        if (!way.steps().isEmpty()) {
            Step last = way.steps().get(way.steps().size() - 1);
            x = last.x();
            y = last.y();
            z = last.z();
        }
        double dx = x - goal.x();
        double dy = y - goal.y();
        double dz = z - goal.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The cell the feet stand in: the floor's top lies within the cell's height. */
    static int[] cellOf(Vec3 feet) {
        return new int[] {(int) Math.floor(feet.x()), (int) Math.floor(feet.y() + 1e-3), (int) Math.floor(feet.z())};
    }
}
