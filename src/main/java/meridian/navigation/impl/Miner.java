package meridian.navigation.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.BlockView;
import meridian.core.api.EntityTracker;
import meridian.core.api.InteractionControl;
import meridian.core.api.Movement;
import meridian.core.api.Player;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import meridian.navigation.api.Goal;
import meridian.navigation.api.NavResult;
import org.slf4j.Logger;

/**
 * Digs: a box out, top layer first, or the blocks of a kind around where it started. Each block is
 * broken from a place within reach - where the body is, or one it walks to ({@link Walker}) -
 * turned to it, with the tool in the hotbar that breaks it best, and whatever stands on the line
 * from the eyes to it broken first (a way dug to an ore in the rock). The drops are walked over at
 * the end. A game's player's body is held for the whole job; the player taking it back ends it.
 */
final class Miner {

    /** A block is broken from no farther than this, eyes to its middle - inside the game's own reach of 5. */
    static final double REACH = 4.5;
    /** The most swings at one block before it is left. */
    static final int MAX_SWINGS = 30;
    /** How many places to stand are tried for one block before it is left. */
    static final int STAND_TRIES = 3;
    /** The most blocks of a kind the search looks at in one go. */
    static final int FIND_LIMIT = 512;
    /** The largest box dug. */
    static final int MAX_BOX = 32_768;
    /** A drop is walked to within this; the server picks it up within 1.75 of the feet. */
    static final double PICKUP_NEAR = 1.0;
    /** Drops are looked for this far around what was broken. */
    static final double DROP_AROUND = 4.0;
    /** How long to wait at a drop for the server to take it (a block's drop waits 0.5 s first). */
    static final long PICKUP_WAIT_MS = 1500;
    /** A place to stand on the line to a block costs this much more for each block in the way. */
    static final double PER_OBSTACLE = 6.0;
    /** A tool's reach is kept this much short, so the dig still lands from where the body ends up. */
    static final double REACH_MARGIN = 0.3;
    /** Once there, a block is still tried this much past the reach it was chosen for. */
    static final double REACH_SLACK = 0.4;

    /** How a job ended. */
    enum Status { DONE, NOTHING, INTERRUPTED, NOT_AVAILABLE }

    /**
     * What a job did: the blocks it broke (those it came for, and those in the way), the ones it
     * left, the drops it picked up, and how many of those it came for are still there.
     */
    record Result(Status status, int broken, int dug, int skipped, int picked, int left) {}

    /** A box of blocks, corners included. */
    record Box(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax) {

        static Box of(int x0, int y0, int z0, int x1, int y1, int z1) {
            return new Box(Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1), Math.max(x0, x1),
                    Math.max(y0, y1), Math.max(z0, z1));
        }

        boolean contains(BlockPos p) {
            return p.x() >= xMin && p.x() <= xMax && p.y() >= yMin && p.y() <= yMax && p.z() >= zMin
                    && p.z() <= zMax;
        }

        long volume() {
            return (long) (xMax - xMin + 1) * (yMax - yMin + 1) * (zMax - zMin + 1);
        }
    }

    private final Movement movement;
    private final World world;
    private final InteractionControl interactions;
    /** Where the drops lie; {@code null} where core does not offer it - then none are walked to. */
    private final EntityTracker entities;
    private final Walker walker;
    private final Executor executor;
    private final Logger log;
    /** The job under way; a new one, or a cancel, moves it on. */
    private final AtomicInteger job = new AtomicInteger();
    /** The break under way, to stop it on a cancel. */
    private volatile CompletableFuture<?> breaking;
    /** The job last started, done once it has ended and given the body back. */
    private volatile CompletableFuture<Result> running = CompletableFuture.completedFuture(null);
    /** How often one block may come back after it was broken - a block that breaks into another. */
    static final int MAX_DIGS_AT = 3;
    private volatile String status = "Idle.";

    Miner(Movement movement, World world, InteractionControl interactions, EntityTracker entities, Walker walker,
          Executor executor, Logger log) {
        this.movement = movement;
        this.world = world;
        this.interactions = interactions;
        this.entities = entities;
        this.walker = walker;
        this.executor = executor;
        this.log = log;
    }

    String status() {
        return status;
    }

    /** Digs out the box, top layer first. */
    CompletableFuture<Result> mine(Box box) {
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

    /**
     * Ends the job under way after the swing it is in; completes once it has ended and given the
     * body back.
     */
    CompletableFuture<Void> cancel() {
        job.incrementAndGet();
        CompletableFuture<?> b = breaking;
        if (b != null) {
            b.cancel(false);
        }
        walker.cancel();
        return running.handle((r, e) -> null);
    }

    /** Whether a job is under way. */
    boolean busy() {
        return !running.isDone();
    }

    private CompletableFuture<Result> start(Job j) {
        CompletableFuture<?> b = breaking;
        if (b != null) {
            b.cancel(false);
        }
        walker.cancel();
        CompletableFuture<Result> before = running;
        CompletableFuture<Result> done = new CompletableFuture<>();
        running = done;
        executor.execute(() -> {
            try {
                // The one before gives the body back first, so it cannot take it from this one.
                try {
                    before.get(1, TimeUnit.MINUTES);
                } catch (Exception ignored) {
                    // ended badly or slowly: this one goes on
                }
                Result r = run(j);
                status = describe(j, r);
                log.info("navigation: mining ended - {}", status);
                done.complete(r);
            } catch (Throwable t) {
                log.warn("navigation: mining failed", t);
                status = "Failed: " + t;
                done.completeExceptionally(t);
            }
        });
        return done;
    }

    // ------------------------------------------------------------------
    // The job
    // ------------------------------------------------------------------

    /** One job's state; only its own thread touches it. */
    private final class Job {
        final int id;
        final Box box;
        final String name;
        final Predicate<BlockView> type;
        final int count;
        final double radius;
        final Vec3 from;
        /** Blocks left behind: no place to break them from, or they would not break. */
        final Set<BlockPos> skipped = new HashSet<>();
        /** Everything broken, those in the way too - where the drops lie. */
        final List<BlockPos> broken = new ArrayList<>();
        /** The blocks it came for that it broke. */
        int dug;
        /** How often each block was broken - one that breaks into another comes back. */
        final java.util.Map<BlockPos, Integer> digs = new java.util.HashMap<>();
        int picked;

        Job(int id, Box box, String name, Predicate<BlockView> type, int count, double radius, Vec3 from) {
            this.id = id;
            this.box = box;
            this.name = name;
            this.type = type;
            this.count = count;
            this.radius = radius;
            this.from = from;
        }

        boolean live() {
            return job.get() == id;
        }
    }

    private Result run(Job j) throws Exception {
        if (!movement.available() || (j.box == null && j.from == null)) {
            return new Result(Status.NOT_AVAILABLE, 0, 0, 0, 0, 0);
        }
        // A game's body is held for the whole job, the walks between blocks included.
        boolean ours = !movement.driving();
        Movement.Wheel wheel = movement.take().orElse(null);
        if (wheel == null) {
            return new Result(Status.NOT_AVAILABLE, 0, 0, 0, 0, 0);
        }
        wheel.released().thenRun(() -> job.compareAndSet(j.id, j.id + 1));
        try {
            int found = 0;
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
                if (dig(j, at)) {
                    j.dug++;
                } else if (j.live()) {
                    j.skipped.add(at);
                    log.info("navigation: left the block at {} {} {}", at.x(), at.y(), at.z());
                }
            }
            if (j.live() && !j.broken.isEmpty()) {
                collect(j);
            }
            Status s = !j.live() ? Status.INTERRUPTED : found == 0 ? Status.NOTHING : Status.DONE;
            return new Result(s, j.broken.size(), j.dug, j.skipped.size(), j.picked, left(j));
        } finally {
            if (ours) {
                wheel.release();
            }
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
                if (j.skipped.contains(p) || nearLava(p)) {
                    continue;
                }
                double d = distSq(me, centre(p));
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
            return best;
        }
        Box box = j.box;
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
                    if (nearLava(p)) {
                        j.skipped.add(p);
                        continue;
                    }
                    double d = distSq(me, centre(p));
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
        Box box = j.box;
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

    /**
     * Breaks one block it came for: with the tool that breaks it best, from a place within that
     * tool's reach - the blocks in the way first. A place that cannot be reached, or from where
     * something will not break, is passed for the next.
     */
    private boolean dig(Job j, BlockPos target) throws Exception {
        Set<BlockPos> tried = new HashSet<>();
        double reach = equip(target);
        for (int attempt = 0; attempt < STAND_TRIES && j.live(); attempt++) {
            Stand s = stand(j, target, tried, reach);
            if (s == null) {
                return false;
            }
            tried.add(s.cell());
            status = progress(j, "going to break " + text(target));
            if (!s.here()) {
                NavResult r = walker.goTo(Goal.at(s.cell())).get(3, TimeUnit.MINUTES);
                if (!j.live()) {
                    return false;
                }
                if (r.status() != NavResult.Status.ARRIVED) {
                    log.info("navigation: could not get to {} to break {} ({})", text(s.cell()), text(target),
                            r.status());
                    continue;
                }
            }
            if (breakFromHere(j, target, s.passage())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Breaks the passage to the block, then what is still on the line from the eyes to it, then
     * the block - each with its own best tool, and only within that tool's reach.
     */
    private boolean breakFromHere(Job j, BlockPos target, List<BlockPos> passage) throws Exception {
        for (BlockPos p : passage) {
            if (!j.live()) {
                return false;
            }
            if (world.blockAt(p.x(), p.y(), p.z()).isAir()) {
                continue;
            }
            if (!breakInReach(j, p, " (the way in)")) {
                return false;
            }
        }
        for (int guard = 0; guard < 16 && j.live(); guard++) {
            Vec3 eye = eye();
            if (eye == null) {
                return false;
            }
            List<BlockPos> inTheWay = obstacles(eye, target);
            if (inTheWay == null) {
                return false;
            }
            BlockPos next = inTheWay.isEmpty() ? target : inTheWay.get(0);
            if (j.box != null && !next.equals(target) && !j.box.contains(next)) {
                return false;                       // a selection is dug inside itself only
            }
            if (!breakInReach(j, next, next.equals(target) ? "" : " (in the way)")) {
                return false;
            }
            if (next.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** Takes the best tool for the block and breaks it, if the body is within that tool's reach. */
    private boolean breakInReach(Job j, BlockPos pos, String why) throws Exception {
        double reach = equip(pos);
        Vec3 eye = eye();
        if (eye == null || distSq(eye, centre(pos)) > (reach + REACH_SLACK) * (reach + REACH_SLACK)) {
            log.info("navigation: {} is out of reach ({})", text(pos), String.format(Locale.ROOT, "%.1f", reach));
            return false;
        }
        status = progress(j, "breaking " + text(pos) + why);
        if (!breakOne(pos)) {
            return false;
        }
        j.broken.add(pos);
        return true;
    }

    /**
     * Takes the hotbar's tool that breaks the block best; how far a dig with what is held then
     * reaches - a shovel's sweep is short - inside {@link #REACH}.
     */
    private double equip(BlockPos pos) throws Exception {
        Block b = world.blockAt(pos.x(), pos.y(), pos.z());
        Player player = world.player().orElse(null);
        if (player != null && !b.isAir()) {
            int slot = player.bestToolSlot(b.type());
            if (slot >= 0 && slot != player.activeHotbarSlot()) {
                interactions.switchHotbarSlot(slot).get(5, TimeUnit.SECONDS);
            }
        }
        String held = player == null ? null : player.heldItem();
        double reach = interactions.digReach(held).orElse(REACH);
        return Math.max(1.0, Math.min(REACH, reach - REACH_MARGIN));
    }

    /** Turns to the block and swings until it is gone. */
    private boolean breakOne(BlockPos pos) throws Exception {
        if (world.blockAt(pos.x(), pos.y(), pos.z()).isAir()) {
            return true;
        }
        movement.face(pos).get(2, TimeUnit.SECONDS);
        CompletableFuture<Boolean> run = interactions.breakBlock(pos, MAX_SWINGS);
        breaking = run;
        try {
            return Boolean.TRUE.equals(run.get(2, TimeUnit.MINUTES));
        } catch (java.util.concurrent.CancellationException cancelled) {
            return false;
        } finally {
            breaking = null;
        }
    }

    // ------------------------------------------------------------------
    // Where to break a block from
    // ------------------------------------------------------------------

    /**
     * A place to stand and break a block from; {@code here} when the body is in it already, and
     * the blocks to break first to make a way in to it ({@link #passage}).
     */
    private record Stand(BlockPos cell, boolean here, double cost, List<BlockPos> passage) {}

    /**
     * The best place to break {@code target} from, not one of {@code tried}: one the body stands in,
     * with its eyes within {@code reach} of the block's middle, not standing on the block itself nor
     * on a block on the line to it. Fewer blocks to break and nearer the body come first; where the
     * body is comes first of all. For a selection only blocks inside it may be in the way; for a
     * block in the rock, the way in is dug the body's height, so the body can go in after its drop.
     */
    private Stand stand(Job j, BlockPos target, Set<BlockPos> tried, double reach) {
        Vec3 me = movement.position();
        if (me == null) {
            return null;
        }
        int[] here = Walker.cellOf(me);
        double eyeH = movement.capabilities().eyeHeight();
        Vec3 aim = centre(target);
        int r = (int) Math.ceil(reach);
        Stand best = null;
        for (int dy = -r - 1; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos cell = new BlockPos(target.x() + dx, target.y() + dy, target.z() + dz);
                    if (tried.contains(cell)) {
                        continue;
                    }
                    boolean isHere = cell.x() == here[0] && cell.y() == here[1] && cell.z() == here[2];
                    OptionalDouble feet = movement.standAt(cell);
                    if (feet.isEmpty()) {
                        continue;
                    }
                    Vec3 eye = isHere ? new Vec3(me.x(), me.y() + eyeH, me.z())
                            : new Vec3(cell.x() + 0.5, feet.getAsDouble() + eyeH, cell.z() + 0.5);
                    if (distSq(eye, aim) > reach * reach) {
                        continue;
                    }
                    BlockPos under = new BlockPos(cell.x(), (int) Math.floor(feet.getAsDouble() - 1e-3), cell.z());
                    if (under.equals(target)) {
                        continue;
                    }
                    List<BlockPos> inTheWay = obstacles(eye, target);
                    if (inTheWay == null || inTheWay.contains(under)) {
                        continue;
                    }
                    List<BlockPos> passage = List.of();
                    if (j.box != null) {
                        if (!inTheWay.stream().allMatch(j.box::contains)) {
                            continue;
                        }
                    } else if (!inTheWay.isEmpty()) {
                        passage = passage(cell, target);
                        if (passage == null || passage.contains(under)) {
                            continue;
                        }
                    }
                    int toBreak = j.box != null ? inTheWay.size() : passage.size();
                    double cost = toBreak * PER_OBSTACLE + Math.sqrt(distSq(me, eye)) - (isHere ? 2 : 0);
                    if (best == null || cost < best.cost()) {
                        best = new Stand(cell, isHere, cost, passage);
                    }
                }
            }
        }
        return best;
    }

    /**
     * The blocks to break for a way in, the body's height, from the cell it stands in to the block:
     * the columns between, a step at a time along x or z, opened at the feet and the head; in the
     * block's own column, what lies between it and the body's height, so its drop falls, or the body
     * drops, to where it can be picked up. Nearest first, the block itself left out; {@code null}
     * when a cell is not known or lava is beside one.
     */
    private List<BlockPos> passage(BlockPos from, BlockPos target) {
        List<BlockPos> cells = new ArrayList<>();
        int x = from.x();
        int z = from.z();
        int y = from.y();
        while (true) {
            int ddx = target.x() - x;
            int ddz = target.z() - z;
            if (Math.abs(ddx) + Math.abs(ddz) <= 1) {
                break;                              // the next column is the block's own
            }
            if (Math.abs(ddx) >= Math.abs(ddz)) {
                x += Integer.signum(ddx);
            } else {
                z += Integer.signum(ddz);
            }
            cells.add(new BlockPos(x, y + 1, z));
            cells.add(new BlockPos(x, y, z));
        }
        if (target.y() > y) {
            for (int k = target.y() - 1; k >= y + 1; k--) {
                cells.add(new BlockPos(target.x(), k, target.z()));
            }
        } else if (target.y() < y) {
            for (int k = y + 1; k >= target.y() + 1; k--) {
                cells.add(new BlockPos(target.x(), k, target.z()));
            }
        }
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : cells) {
            if (c.equals(target)) {
                continue;
            }
            if (!world.known(c.x(), c.y(), c.z())) {
                return null;
            }
            if (world.blockAt(c.x(), c.y(), c.z()).isAir()) {
                continue;
            }
            if (nearLava(c)) {
                return null;
            }
            out.add(c);
        }
        return out;
    }

    /**
     * The blocks on the line from {@code eye} to the middle of {@code target}, nearest the eyes
     * first, the target left out; {@code null} when a cell on it is not known.
     */
    private List<BlockPos> obstacles(Vec3 eye, BlockPos target) {
        Vec3 aim = centre(target);
        double dx = aim.x() - eye.x();
        double dy = aim.y() - eye.y();
        double dz = aim.z() - eye.z();
        int x = (int) Math.floor(eye.x());
        int y = (int) Math.floor(eye.y());
        int z = (int) Math.floor(eye.z());
        int sx = dx > 0 ? 1 : -1;
        int sy = dy > 0 ? 1 : -1;
        int sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tx = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - eye.x() : eye.x() - x) * tdx;
        double ty = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - eye.y() : eye.y() - y) * tdy;
        double tz = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - eye.z() : eye.z() - z) * tdz;
        List<BlockPos> out = new ArrayList<>();
        for (int guard = 0; guard < 64; guard++) {
            if (x == target.x() && y == target.y() && z == target.z()) {
                return out;
            }
            if (!world.known(x, y, z)) {
                return null;
            }
            Block b = world.blockAt(x, y, z);
            if (!b.isAir()) {
                BlockPos base = b.base();
                if (!base.equals(target) && !out.contains(base)) {
                    out.add(base);
                }
            }
            if (tx <= ty && tx <= tz) {
                x += sx;
                tx += tdx;
            } else if (ty <= tz) {
                y += sy;
                ty += tdy;
            } else {
                z += sz;
                tz += tdz;
            }
            if (Math.min(tx, Math.min(ty, tz)) > 1 + 1e-9 && !(x == target.x() && y == target.y() && z == target.z())) {
                // Past the target's middle without entering its cell: the line ends there.
                return out;
            }
        }
        return out;
    }

    /** Whether lava is next to the block, so breaking it would let it in. */
    private boolean nearLava(BlockPos p) {
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) {
            Movement.Cell c = movement.cell(new BlockPos(p.x() + d[0], p.y() + d[1], p.z() + d[2]));
            if (c != null && c.fluid() != null && c.hurts()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // The drops
    // ------------------------------------------------------------------

    /** Walks over the drops that lie around what was broken, nearest first, until none is left. */
    private void collect(Job j) throws Exception {
        if (entities == null) {
            return;
        }
        int[] area = area(j.broken);
        Set<Integer> passed = new HashSet<>();
        Set<Integer> retried = new HashSet<>();
        for (int round = 0; round < 256 && j.live(); round++) {
            Vec3 me = movement.position();
            if (me == null) {
                return;
            }
            int best = -1;
            double bestD = Double.MAX_VALUE;
            Vec3 bestAt = null;
            for (int e : entities.trackedEntities()) {
                if (passed.contains(e) || entities.itemOf(e).isEmpty()) {
                    continue;
                }
                Vec3 at = entities.positionOf(e).orElse(null);
                if (at == null || !inArea(area, at)) {
                    continue;
                }
                double d = distSq(me, at);
                if (d < bestD) {
                    bestD = d;
                    best = e;
                    bestAt = at;
                }
            }
            if (best < 0) {
                return;
            }
            status = progress(j, "picking up " + entities.itemOf(best).orElse("a drop"));
            int[] c = Walker.cellOf(bestAt);
            BlockPos cell = new BlockPos(c[0], c[1], c[2]);
            Goal goal = movement.standAt(cell).isPresent() ? Goal.at(cell) : Goal.near(cell, PICKUP_NEAR);
            NavResult r = walker.goTo(goal).get(3, TimeUnit.MINUTES);
            if (!j.live()) {
                return;
            }
            boolean gone = false;
            for (long waited = 0; waited < PICKUP_WAIT_MS && !gone; waited += 100) {
                Thread.sleep(100);
                gone = entities.itemOf(best).isEmpty();
            }
            if (gone) {
                j.picked++;
            } else {
                // Not taken from there: tried once more if it has moved, else left.
                Vec3 now = entities.positionOf(best).orElse(null);
                boolean moved = now != null && distSq(now, bestAt) > 0.25;
                if (!moved || r.status() != NavResult.Status.ARRIVED || !retried.add(best)) {
                    passed.add(best);
                }
            }
        }
    }

    /** The box around what was broken, {@link #DROP_AROUND} wider each way. */
    private static int[] area(List<BlockPos> broken) {
        int[] a = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE};
        for (BlockPos p : broken) {
            a[0] = Math.min(a[0], p.x());
            a[1] = Math.min(a[1], p.y());
            a[2] = Math.min(a[2], p.z());
            a[3] = Math.max(a[3], p.x());
            a[4] = Math.max(a[4], p.y());
            a[5] = Math.max(a[5], p.z());
        }
        return a;
    }

    private static boolean inArea(int[] a, Vec3 p) {
        return p.x() >= a[0] - DROP_AROUND && p.x() <= a[3] + 1 + DROP_AROUND && p.y() >= a[1] - DROP_AROUND
                && p.y() <= a[4] + 1 + DROP_AROUND && p.z() >= a[2] - DROP_AROUND && p.z() <= a[5] + 1 + DROP_AROUND;
    }

    // ------------------------------------------------------------------

    private Vec3 eye() {
        Vec3 feet = movement.position();
        return feet == null ? null : new Vec3(feet.x(), feet.y() + movement.capabilities().eyeHeight(), feet.z());
    }

    private static Vec3 centre(BlockPos p) {
        return new Vec3(p.x() + 0.5, p.y() + 0.5, p.z() + 0.5);
    }

    private static double distSq(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
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
