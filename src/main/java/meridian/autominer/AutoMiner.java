package meridian.autominer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import meridian.core.api.Block;
import meridian.core.api.BlockBox;
import meridian.core.api.BlockPos;
import meridian.core.api.BlockView;
import meridian.core.api.Movement;
import meridian.core.api.Vec3;
import meridian.core.api.Worker;
import meridian.core.api.World;
import org.slf4j.Logger;

/**
 * What to dig, and in what order: a box out, top layer first, or the blocks of a kind around where
 * it started, nearest first. Each block is core's {@link Worker}'s to get at and break; the drops
 * around what was broken are picked up at the end. A game's player's body is held for the whole
 * job, so it stays held between blocks; the player taking it back, or a Go or Stop in core's
 * panel, ends the job.
 */
final class AutoMiner {

    /** The most blocks of a kind the search looks at in one go. */
    static final int FIND_LIMIT = 512;
    /** The largest box dug. */
    static final int MAX_BOX = 32_768;
    /** How often one block may come back after it was broken - a block that breaks into another. */
    static final int MAX_DIGS_AT = 3;
    /** Drops are looked for this far around what was broken. */
    static final int DROP_AROUND = 4;

    /** How a job ended. */
    enum Status { DONE, NOTHING, INTERRUPTED, NOT_AVAILABLE }

    /**
     * What a job did: the blocks it broke (those it came for, and those in the way), how many of
     * those it came for, the ones it left, the drops it picked up, and how many of those it came for
     * are still there.
     */
    record Result(Status status, int broken, int dug, int skipped, int picked, int left) {}

    private final Movement movement;
    private final World world;
    private final Worker worker;
    private final Executor executor;
    private final Logger log;
    /** The job under way; a new one, or a cancel, moves it on. */
    private final AtomicInteger job = new AtomicInteger();
    /** The job last started, done once it has ended and given the body back. */
    private volatile CompletableFuture<Result> running = CompletableFuture.completedFuture(null);
    private volatile String status = "Idle.";

    AutoMiner(Movement movement, World world, Worker worker, Executor executor, Logger log) {
        this.movement = movement;
        this.world = world;
        this.worker = worker;
        this.executor = executor;
        this.log = log;
    }

    String status() {
        return status;
    }

    /** Whether a job is under way. */
    boolean busy() {
        return !running.isDone();
    }

    /** Digs out the box, top layer first, inside itself only. */
    CompletableFuture<Result> mine(BlockBox box) {
        if (box.volume() > MAX_BOX) {
            status = "The selection is too big: " + box.volume() + " blocks, at most " + MAX_BOX + ".";
            return CompletableFuture.completedFuture(new Result(Status.NOTHING, 0, 0, 0, 0, 0));
        }
        return start(new Job(job.incrementAndGet(), box, null, null, 0, 0, null));
    }

    /**
     * Digs up to {@code count} blocks whose name holds {@code name}, within {@code radius} of where
     * the body stands now, nearest first.
     */
    CompletableFuture<Result> mine(String name, int count, double radius) {
        if (name == null || name.isBlank()) {
            status = "Say which blocks: a part of their name, like Ore_Copper.";
            return CompletableFuture.completedFuture(new Result(Status.NOTHING, 0, 0, 0, 0, 0));
        }
        Vec3 from = movement.position();
        Predicate<BlockView> type = t -> t.name() != null && t.name().contains(name);
        return start(new Job(job.incrementAndGet(), null, name, type, Math.max(1, count), radius, from));
    }

    /** Ends the job under way; completes once it has ended and given the body back. */
    CompletableFuture<Void> cancel() {
        job.incrementAndGet();
        worker.cancel();
        return running.handle((r, e) -> null);
    }

    private CompletableFuture<Result> start(Job j) {
        worker.cancel();
        CompletableFuture<Result> before = running;
        CompletableFuture<Result> done = new CompletableFuture<>();
        running = done;
        executor.execute(() -> {
            try {
                // The one before gives its hold back first.
                try {
                    before.get(1, TimeUnit.MINUTES);
                } catch (Exception ignored) {
                    // ended badly or slowly: this one goes on
                }
                Result r = run(j);
                status = describe(j, r);
                log.info("auto-miner: {}", status);
                done.complete(r);
            } catch (Throwable t) {
                log.warn("auto-miner: mining failed", t);
                status = "Failed: " + t;
                done.completeExceptionally(t);
            }
        });
        return done;
    }

    // ------------------------------------------------------------------

    /** One job's state; only its own thread touches it. */
    private final class Job {
        final int id;
        final BlockBox box;
        final String name;
        final Predicate<BlockView> type;
        final int count;
        final double radius;
        final Vec3 from;
        /** Blocks left behind: no place to break them from, lava beside, or they would not break. */
        final Set<BlockPos> skipped = new HashSet<>();
        /** Everything broken, those in the way too - where the drops lie. */
        final List<BlockPos> broken = new ArrayList<>();
        /** How often each block was broken - one that breaks into another comes back. */
        final Map<BlockPos, Integer> digs = new HashMap<>();
        /** The blocks it came for that it broke. */
        int dug;
        int picked;
        /** The worker was stopped under it - the player took the body, or a Go or Stop. */
        boolean stopped;

        Job(int id, BlockBox box, String name, Predicate<BlockView> type, int count, double radius, Vec3 from) {
            this.id = id;
            this.box = box;
            this.name = name;
            this.type = type;
            this.count = count;
            this.radius = radius;
            this.from = from;
        }

        boolean live() {
            return job.get() == id && !stopped;
        }
    }

    private Result run(Job j) throws Exception {
        if (!worker.available() || (j.box == null && j.from == null)) {
            return new Result(Status.NOT_AVAILABLE, 0, 0, 0, 0, 0);
        }
        // The job's own hold on a game's body: it stays held between blocks.
        Movement.Wheel wheel = movement.take().orElse(null);
        if (wheel == null) {
            return new Result(Status.NOT_AVAILABLE, 0, 0, 0, 0, 0);
        }
        wheel.released().thenRun(() -> job.compareAndSet(j.id, j.id + 1));
        try {
            int found = 0;
            Worker.Access access = j.box != null ? Worker.Access.inside(j.box) : Worker.Access.ANY;
            while (j.live()) {
                Block target = next(j);
                if (target == null) {
                    break;
                }
                found++;
                BlockPos at = new BlockPos(target.x(), target.y(), target.z());
                if (j.digs.merge(at, 1, Integer::sum) > MAX_DIGS_AT) {
                    j.skipped.add(at);
                    continue;
                }
                status = progress(j, "digging " + text(at));
                Worker.Dug d = worker.dig(at, access).get(10, TimeUnit.MINUTES);
                j.broken.addAll(d.broken());
                switch (d.outcome()) {
                    case BROKEN -> j.dug++;
                    case INTERRUPTED, NOT_AVAILABLE -> j.stopped = true;
                    default -> {
                        j.skipped.add(at);
                        log.info("auto-miner: left the block at {} ({})", text(at), d.outcome());
                    }
                }
            }
            if (j.live() && !j.broken.isEmpty()) {
                status = progress(j, "picking up the drops");
                j.picked = worker.pickUp(area(j.broken).grow(DROP_AROUND)).get(10, TimeUnit.MINUTES);
            }
            Status s = !j.live() ? Status.INTERRUPTED : found == 0 ? Status.NOTHING : Status.DONE;
            return new Result(s, j.broken.size(), j.dug, j.skipped.size(), j.picked, left(j));
        } finally {
            wheel.release();
        }
    }

    /** The next block to dig, or {@code null} when none is left. */
    private Block next(Job j) {
        Vec3 me = movement.position();
        if (me == null) {
            return null;
        }
        if (j.box == null) {
            if (j.dug >= j.count) {
                return null;
            }
            Block best = null;
            double bestD = Double.MAX_VALUE;
            for (Block b : world.find(j.from, j.radius, j.type, FIND_LIMIT)) {
                BlockPos p = new BlockPos(b.x(), b.y(), b.z());
                if (j.skipped.contains(p)) {
                    continue;
                }
                double d = distSq(me, p);
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
            return best;
        }
        BlockBox box = j.box;
        for (int y = box.yMax(); y >= box.yMin(); y--) {
            Block best = null;
            double bestD = Double.MAX_VALUE;
            for (int x = box.xMin(); x <= box.xMax(); x++) {
                for (int z = box.zMin(); z <= box.zMax(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (j.skipped.contains(p) || !world.known(x, y, z)) {
                        continue;
                    }
                    Block b = world.blockAt(x, y, z);
                    if (b.isAir()) {
                        continue;
                    }
                    double d = distSq(me, p);
                    if (d < bestD) {
                        bestD = d;
                        best = b;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /** How many of the blocks it came for are still there. */
    private int left(Job j) {
        if (j.box == null) {
            return Math.max(0, j.count - j.dug);
        }
        int n = 0;
        BlockBox box = j.box;
        for (int y = box.yMin(); y <= box.yMax(); y++) {
            for (int x = box.xMin(); x <= box.xMax(); x++) {
                for (int z = box.zMin(); z <= box.zMax(); z++) {
                    if (world.known(x, y, z) && !world.blockAt(x, y, z).isAir()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** The box around what was broken. */
    private static BlockBox area(List<BlockPos> broken) {
        BlockPos first = broken.get(0);
        BlockBox a = BlockBox.of(first, first);
        for (BlockPos p : broken) {
            a = BlockBox.of(Math.min(a.xMin(), p.x()), Math.min(a.yMin(), p.y()), Math.min(a.zMin(), p.z()),
                    Math.max(a.xMax(), p.x()), Math.max(a.yMax(), p.y()), Math.max(a.zMax(), p.z()));
        }
        return a;
    }

    private static double distSq(Vec3 a, BlockPos p) {
        double dx = a.x() - (p.x() + 0.5);
        double dy = a.y() - (p.y() + 0.5);
        double dz = a.z() - (p.z() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static String text(BlockPos p) {
        return p.x() + " " + p.y() + " " + p.z();
    }

    private static String progress(Job j, String doing) {
        String what = j.box != null ? "the selection" : j.dug + " of " + j.count + " " + j.name;
        return "Mining " + what + ": " + j.broken.size() + " broken, " + j.skipped.size() + " left behind - " + doing
                + ".";
    }

    private static String describe(Job j, Result r) {
        String head = switch (r.status()) {
            case DONE -> "Done";
            case NOTHING -> j.box != null ? "Nothing to dig in the selection" : "No " + j.name + " within " + (int) j.radius;
            case INTERRUPTED -> "Stopped";
            case NOT_AVAILABLE -> "Cannot move the player here";
        };
        if (r.status() == Status.NOTHING || r.status() == Status.NOT_AVAILABLE) {
            return head + ".";
        }
        return String.format(Locale.ROOT, "%s: %d broken (%d asked for), %d left behind, %d drop(s) picked up%s.", head,
                r.broken(), r.dug(), r.skipped(), r.picked(), r.left() > 0 ? ", " + r.left() + " still there" : "");
    }
}
