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
 * What to dig, and in what order: a box out (bands 2 high from the top, a snake of rows, each
 * column top down - see plan), or the blocks of a kind around where
 * it started, nearest first. Each block is core's {@link Worker}'s to get at and break - with a
 * shovel for soil, if asked. The drops around what was broken are picked up as {@link Options}
 * says, and what the job dug is put into a chest when the inventory is full and at the end, if
 * asked. A game's player's body is held for the whole job, so it stays held between blocks; the
 * player taking it back, or a Go or Stop in core's panel, ends the job.
 */
final class AutoMiner {

    /** The most blocks of a kind the search looks at in one go. */
    static final int FIND_LIMIT = 512;
    /** Blocks of a kind are looked for this far round where the body is: about what the game has loaded. */
    static final double SEARCH = 48.0;
    /** The id of the box that outlines the block being dug ({@link meridian.core.api.DebugRender#worldBox}). */
    static final String TARGET_BOX = "meridian-auto-miner:target";
    /**
     * The next block is the one cheapest to go on to: its distance from the eyes, plus this many
     * blocks for each radian the head turns to it - a quarter turn costs as much as 8 blocks, so the
     * one behind a block just broken comes before one beside it nearer by: dug on the way the head
     * looks, not round and round again for each ring.
     */
    static final double PER_RADIAN = 5.0;
    /** Past this far from the eyes the turn of the head weighs less, by the distance. */
    static final double TURN_NEAR = 6.0;
    /** A block beside the one broken last comes this much nearer: a player digs on from where they dug. */
    static final double BESIDE = 1.5;
    /** The largest box dug. */
    static final int MAX_BOX = 32_768;
    /** How often one block may come back after it was broken - a block that breaks into another. */
    static final int MAX_DIGS_AT = 3;
    /** Drops are looked for this far around what was broken. */
    static final int DROP_AROUND = 4;
    /** An item first seen this near a broken block's middle is that block's drop. */
    static final double DROP_FROM = 1.5;
    /** The longest the last block's drop is waited for before the drops are picked up. */
    static final long SETTLE_MS = 800;
    /** A chest to unload into is looked for this far from the body. */
    static final double CHEST_AROUND = 32;
    /** The most chests tried in one unloading. */
    static final int CHESTS_TRIED = 8;
    /** The player's own sections what the job dug can be in, by the names core keeps them under. */
    static final java.util.Set<String> CARRIED = java.util.Set.of("Hotbar", "Storage", "Backpack");

    /** When the drops are picked up. */
    enum PickUp {
        /** Never: they stay where they fell. */
        NONE,
        /** Once everything is dug. */
        AT_END,
        /** Whenever {@link Options#every} items lie about, and the rest at the end. */
        EVERY,
        /**
         * Once everything is dug: every item the job dropped, wherever it went since - rolled, fell
         * down a hole, floated off - not only those still about what was broken. Items that lay
         * there before are left.
         */
        ALL
    }

    /** Where what the job dug goes. */
    enum Unload {
        /** It stays with the player. */
        OFF,
        /** Into the nearest chest with room. */
        NEAREST,
        /** Into {@link Options#chest}. */
        CHOSEN
    }

    /**
     * How a job goes.
     *
     * @param shovel take a shovel for soil - dirt, gravel, sand - when the hotbar has one (at the
     *               edge of a selection core takes a tool that does not sweep past it)
     * @param pickUp when the drops are picked up
     * @param every  with {@link PickUp#EVERY}, how many items on the ground make a round of picking up
     * @param unload where what the job dug goes, when the inventory is full and at the end
     * @param chest  with {@link Unload#CHOSEN}, the chest
     */
    record Options(boolean shovel, PickUp pickUp, int every, Unload unload, BlockPos chest) {

        static final Options DEFAULT = new Options(true, PickUp.AT_END, 20, Unload.OFF, null);

        Worker.Tools tools() {
            return shovel ? new Worker.Tools(Map.of("Soils", "Shovel")) : Worker.Tools.BEST;
        }
    }

    /** How a job ended. */
    enum Status { DONE, NOTHING, INTERRUPTED, NOT_AVAILABLE }

    /**
     * What a job did: the blocks it broke (those it came for, and those in the way), how many of
     * those it came for, the ones it left, the drops it picked up, and how many of those it came for
     * are still there.
     */
    record Result(Status status, int broken, int dug, int skipped, int picked, int left, int unloaded) {

        Result(Status status, int broken, int dug, int skipped, int picked, int left) {
            this(status, broken, dug, skipped, picked, left, 0);
        }
    }

    private final Movement movement;
    private final World world;
    private final Worker worker;
    /** What the player holds; {@code null} where core does not offer it - then nothing is unloaded. */
    private final meridian.core.api.PlayerState inventory;
    /** The drops lying about; {@code null} where core does not offer it - then no round is counted. */
    private final meridian.core.api.EntityTracker entities;
    private final Executor executor;
    private final Logger log;
    /** The job under way; a new one, or a cancel, moves it on. */
    private final AtomicInteger job = new AtomicInteger();
    /** The job last started, done once it has ended and given the body back. */
    private volatile CompletableFuture<Result> running = CompletableFuture.completedFuture(null);
    private volatile String status = "Idle.";

    AutoMiner(Movement movement, World world, Worker worker, meridian.core.api.PlayerState inventory,
              meridian.core.api.EntityTracker entities, meridian.core.api.DebugRender debug, Executor executor,
              Logger log) {
        this.debug = debug;
        this.movement = movement;
        this.world = world;
        this.worker = worker;
        this.inventory = inventory;
        this.entities = entities;
        this.executor = executor;
        this.log = log;
    }

    /** Outlines the block being dug; {@code null} where core does not draw - then nothing is outlined. */
    private final meridian.core.api.DebugRender debug;
    private volatile boolean showTarget = true;

    String status() {
        return status;
    }

    /** Whether a job is under way. */
    boolean busy() {
        return !running.isDone();
    }

    /** Digs out the box, inside itself only, by its {@link #plan}. */
    CompletableFuture<Result> mine(BlockBox box, Options options) {
        if (box.volume() > MAX_BOX) {
            status = "The selection is too big: " + box.volume() + " blocks, at most " + MAX_BOX + ".";
            return CompletableFuture.completedFuture(new Result(Status.NOTHING, 0, 0, 0, 0, 0));
        }
        return start(new Job(job.incrementAndGet(), box, null, null, 0, 0, null, options));
    }

    /**
     * Digs {@code count} blocks whose name holds {@code name} - with {@code count} under 1, as long
     * as there are any - each looked for round where the body is then ({@link #SEARCH}), the one
     * cheapest to go on to first.
     */
    CompletableFuture<Result> mine(String name, int count, Options options) {
        if (name == null || name.isBlank()) {
            status = "Say which blocks: a part of their name, like Ore_Copper.";
            return CompletableFuture.completedFuture(new Result(Status.NOTHING, 0, 0, 0, 0, 0));
        }
        Vec3 from = movement.position();
        Predicate<BlockView> type = t -> t.name() != null && t.name().contains(name);
        return start(new Job(job.incrementAndGet(), null, name, type, count < 1 ? -1 : count, SEARCH, from, options));
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
        final Options options;
        /** What the player held when it started, by item - the rest is what the job dug. */
        Map<String, Integer> start = Map.of();
        int unloaded;
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
        /** The items on the ground core saw when it started: not the job's drops. */
        final Set<Integer> lying = new HashSet<>();
        /** The items the job dropped: first seen beside a block it broke. */
        final Set<Integer> drops = new HashSet<>();
        /** A box's order of digging ({@link #plan}), worked out at its first block, and each block's place in it. */
        List<BlockPos> plan;
        Map<BlockPos, Integer> planned;
        /** The block the job went for last - what the next is looked for beside. */
        BlockPos target;
        /** Whether the plan's rows run along x ({@link #rowsAlongX}). */
        boolean alongX;

        Job(int id, BlockBox box, String name, Predicate<BlockView> type, int count, double radius, Vec3 from,
            Options options) {
            this.options = options == null ? Options.DEFAULT : options;
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
            j.start = carried();
            if (entities != null) {
                for (int e : entities.trackedEntities()) {
                    if (entities.itemOf(e).isPresent()) {
                        j.lying.add(e);
                    }
                }
            }
            Worker.Access access = j.box != null ? Worker.Access.inside(j.box) : Worker.Access.ANY;
            Worker.Tools tools = j.options.tools();
            while (j.live()) {
                if (j.options.unload() != Unload.OFF && full()) {
                    unload(j);
                }
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
                j.target = at;
                mark(at);
                Worker.Dug d = worker.dig(at, access, tools).get(10, TimeUnit.MINUTES);
                j.broken.addAll(d.broken());
                if (j.options.pickUp() == PickUp.ALL && !d.broken().isEmpty()) {
                    // Its drop taken as the job's while it is still in sight: once the body has
                    // gone on and it is out of view, it comes back as an item like any other.
                    settle(j);
                }
                claim(j);
                switch (d.outcome()) {
                    case BROKEN -> j.dug++;
                    case INTERRUPTED, NOT_AVAILABLE -> j.stopped = true;
                    default -> {
                        j.skipped.add(at);
                        log.info("auto-miner: left the block at {} ({})", text(at), d.outcome());
                    }
                }
                if (j.live() && j.options.pickUp() == PickUp.EVERY && !j.broken.isEmpty()
                        && lyingAbout(area(j.broken).grow(DROP_AROUND)) >= j.options.every()) {
                    pickUp(j);
                }
            }
            if (j.live() && !j.broken.isEmpty() && j.options.pickUp() != PickUp.NONE) {
                settle(j);
            }
            if (j.live() && !j.broken.isEmpty() && j.options.pickUp() == PickUp.ALL) {
                claim(j);
                status = progress(j, "picking up all it dropped");
                j.picked += worker.pickUp(j.drops).get(10, TimeUnit.MINUTES);
            } else if (j.live() && !j.broken.isEmpty() && j.options.pickUp() != PickUp.NONE) {
                pickUp(j);
            }
            if (j.live() && j.options.unload() != Unload.OFF) {
                unload(j);
            }
            Status s = !j.live() ? Status.INTERRUPTED : found == 0 ? Status.NOTHING : Status.DONE;
            return new Result(s, j.broken.size(), j.dug, j.skipped.size(), j.picked, left(j), j.unloaded);
        } finally {
            unmark();
            wheel.release();
        }
    }

    /** Whether the block being dug is outlined. */
    void showTarget(boolean on) {
        showTarget = on;
        if (!on) {
            unmark();
        }
    }

    /** Outlines the block the job goes to dig now, seen through walls. */
    private void mark(BlockPos p) {
        if (debug != null && showTarget) {
            debug.worldBox(TARGET_BOX, p.x() + 0.5, p.y() + 0.5, p.z() + 0.5, 1.02, 1.02, 1.02, 1f, 0.55f, 0.1f, 0.35f);
        }
    }

    private void unmark() {
        if (debug != null) {
            debug.clearWorldBox(TARGET_BOX);
        }
    }

    /**
     * Waits for the last block's drop to show: the server sends it a moment after the block goes, and
     * a round of picking up straight after would not see it. Done once an item shows by that block,
     * or after {@link #SETTLE_MS}.
     */
    private void settle(Job j) throws InterruptedException {
        if (entities == null) {
            return;
        }
        BlockPos last = j.broken.get(j.broken.size() - 1);
        for (long waited = 0; waited < SETTLE_MS; waited += 50) {
            for (int e : entities.trackedEntities()) {
                if (j.lying.contains(e) || entities.itemOf(e).isEmpty()) {
                    continue;
                }
                Vec3 first = entities.firstSeenAt(e).orElse(entities.positionOf(e).orElse(null));
                if (first != null && Math.abs(first.x() - (last.x() + 0.5)) <= DROP_FROM
                        && Math.abs(first.y() - (last.y() + 0.5)) <= DROP_FROM
                        && Math.abs(first.z() - (last.z() + 0.5)) <= DROP_FROM) {
                    return;
                }
            }
            Thread.sleep(50);
        }
    }

    /**
     * Takes as the job's the items that came since it started and were first seen by a block it broke
     * (a block's drop shows up in or by its cell) - wherever they are now.
     */
    private void claim(Job j) {
        if (entities == null || j.broken.isEmpty()) {
            return;
        }
        BlockBox near = area(j.broken).grow(1);
        for (int e : entities.trackedEntities()) {
            if (j.lying.contains(e) || j.drops.contains(e) || entities.itemOf(e).isEmpty()) {
                continue;
            }
            Vec3 first = entities.firstSeenAt(e).orElse(null);
            if (first == null || !near.contains((int) Math.floor(first.x()), (int) Math.floor(first.y()),
                    (int) Math.floor(first.z()))) {
                continue;
            }
            for (BlockPos b : j.broken) {
                double dx = first.x() - (b.x() + 0.5);
                double dy = first.y() - (b.y() + 0.5);
                double dz = first.z() - (b.z() + 0.5);
                if (dx * dx + dy * dy + dz * dz <= DROP_FROM * DROP_FROM) {
                    j.drops.add(e);
                    break;
                }
            }
        }
    }

    /** A round of picking up the drops around what was broken. */
    private void pickUp(Job j) throws Exception {
        status = progress(j, "picking up the drops");
        j.picked += worker.pickUp(area(j.broken).grow(DROP_AROUND)).get(10, TimeUnit.MINUTES);
    }

    /** How many items lie on the ground in the area. */
    private int lyingAbout(BlockBox area) {
        if (entities == null) {
            return 0;
        }
        int n = 0;
        for (int e : entities.trackedEntities()) {
            if (entities.itemOf(e).isEmpty()) {
                continue;
            }
            Vec3 at = entities.positionOf(e).orElse(null);
            if (at != null && area.contains((int) Math.floor(at.x()), (int) Math.floor(at.y()), (int) Math.floor(at.z()))) {
                n += Math.max(1, entities.itemQuantityOf(e));
            }
        }
        return n;
    }

    /** How many of each item the player carries: the hotbar, the storage and the backpack. */
    private Map<String, Integer> carried() {
        Map<String, Integer> out = new HashMap<>();
        if (inventory == null) {
            return out;
        }
        for (Map.Entry<String, meridian.core.api.PlayerState.Section> e : inventory.inventories().entrySet()) {
            if (CARRIED.contains(e.getKey())) {
                for (meridian.core.api.Containers.Item item : e.getValue().items().values()) {
                    if (item != null) {
                        out.merge(item.id(), item.quantity(), Integer::sum);
                    }
                }
            }
        }
        return out;
    }

    /** Whether no slot is left free in the hotbar, the storage or the backpack. */
    private boolean full() {
        if (inventory == null) {
            return false;
        }
        int free = 0;
        boolean any = false;
        for (Map.Entry<String, meridian.core.api.PlayerState.Section> e : inventory.inventories().entrySet()) {
            if (CARRIED.contains(e.getKey())) {
                any = true;
                free += Math.max(0, e.getValue().capacity() - e.getValue().items().size());
            }
        }
        return any && free == 0;
    }

    /**
     * Puts what the job dug - what the player carries more of than when it started - into the chosen
     * chest, or the nearest ones with room in turn.
     */
    private void unload(Job j) throws Exception {
        Map<String, Integer> dug = new HashMap<>();
        carried().forEach((item, n) -> {
            int more = n - j.start.getOrDefault(item, 0);
            if (more > 0) {
                dug.put(item, more);
            }
        });
        if (dug.isEmpty()) {
            return;
        }
        List<BlockPos> chests = new ArrayList<>();
        if (j.options.unload() == Unload.CHOSEN) {
            if (j.options.chest() != null) {
                chests.add(j.options.chest());
            }
        } else {
            Vec3 me = movement.position();
            if (me != null) {
                for (Block b : world.find(me, CHEST_AROUND, t -> t.name() != null && t.name().contains("Chest"),
                        CHESTS_TRIED)) {
                    chests.add(b.base());
                }
            }
        }
        for (BlockPos chest : chests) {
            if (!j.live() || dug.isEmpty()) {
                break;
            }
            status = progress(j, "unloading into the chest at " + text(chest));
            Map<String, Integer> moved = worker.deposit(chest, dug).get(5, TimeUnit.MINUTES);
            moved.forEach((item, n) -> {
                j.unloaded += n;
                dug.computeIfPresent(item, (k, v) -> v - n > 0 ? v - n : null);
            });
        }
        if (!dug.isEmpty()) {
            log.info("auto-miner: {} item(s) found no room in a chest", dug.values().stream().mapToInt(Integer::intValue).sum());
        }
    }

    /**
     * The next block to dig, or {@code null} when none is left: a box's next by its {@link #plan};
     * of the blocks of the kind, the one cheapest to go on to - near the eyes, little turn of the head from
     * where it looks, beside the block broken last ({@link #cost}). The way a player digs, block by
     * block, not swinging round to a block behind them.
     */
    private Block next(Job j) {
        Vec3 me = movement.position();
        if (me == null) {
            return null;
        }
        Vec3 eye = new Vec3(me.x(), me.y() + movement.capabilities().eyeHeight(), me.z());
        BlockPos last = j.broken.isEmpty() ? null : j.broken.get(j.broken.size() - 1);
        // The way on is reckoned from the block broken last - where the head was meant to be - not
        // from where it stopped: a look stops short of a block's middle, and that would swap a near
        // block and a far one the same way.
        Vec3 look = last != null ? along(eye, last) : null;
        if (look == null) {
            Movement.State st = movement.state();
            look = st == null ? null : new meridian.core.api.Look(st.yaw(), st.pitch()).direction();
        }
        if (j.box == null) {
            if (j.count > 0 && j.dug >= j.count) {
                return null;
            }
            List<Block> found = world.find(me, j.radius, j.type, FIND_LIMIT);
            // First what touches the block gone for last and is dug from where the body stands -
            // the nearest of those: on along the vein, no step.
            Block beside = null;
            double besideD = Double.MAX_VALUE;
            if (j.target != null) {
                for (Block b : found) {
                    BlockPos p = new BlockPos(b.x(), b.y(), b.z());
                    if (j.skipped.contains(p) || !touches(j.target, p)) {
                        continue;
                    }
                    double d = distance(eye, p);
                    if (d < besideD && worker.digsFromHere(p, Worker.Access.ANY, j.options.tools())) {
                        besideD = d;
                        beside = b;
                    }
                }
            }
            if (beside != null) {
                return beside;
            }
            Block best = null;
            double bestD = Double.MAX_VALUE;
            for (Block b : found) {
                BlockPos p = new BlockPos(b.x(), b.y(), b.z());
                if (j.skipped.contains(p)) {
                    continue;
                }
                double d = cost(eye, look, last, p);
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
            return best;
        }
        // A box goes by its plan, worked out once: the first block of it still there...
        if (j.plan == null) {
            j.plan = plan(j.box, me);
            j.alongX = rowsAlongX(j.box, me);
            j.planned = new HashMap<>();
            for (int i = 0; i < j.plan.size(); i++) {
                j.planned.put(j.plan.get(i), i);
            }
        }
        BlockBox box = j.box;
        Block planned = null;
        for (BlockPos p : j.plan) {
            Block b = left(j, p.x(), p.y(), p.z());
            if (b != null) {
                planned = b;
                break;
            }
        }
        if (planned == null || j.target == null) {
            return planned;
        }
        // Gone on from the block broken last - what the job went for, or one the worker broke in the
        // way to it: where the digging is now.
        BlockPos prev = last != null ? last : j.target;
        int band = band(box, planned.y());
        // ... but first the block under the one gone for last, in its band: a column is finished.
        if (band(box, prev.y() - 1) == band && box.contains(prev.x(), prev.y() - 1, prev.z())) {
            Block under = left(j, prev.x(), prev.y() - 1, prev.z());
            if (under != null) {
                return under;
            }
        }
        // The plan's next touching it: on along the row - a step along it is how a row is dug.
        BlockPos next = new BlockPos(planned.x(), planned.y(), planned.z());
        if (touches(prev, next)) {
            return planned;
        }
        // The plan jumps along its row (a block left behind, one broken in the way): first, of the row
        // being dug, what touches the block gone for last and is dug from where the body stands, its
        // column above it dug - no step for it. Of those the one the plan takes first. Not one of the
        // next row: that would leave holes behind and come back for them.
        int row = j.alongX ? planned.z() : planned.x();
        Block beside = null;
        int besideAt = Integer.MAX_VALUE;
        Worker.Access access = Worker.Access.inside(box);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = prev.x() + dx;
                    int y = prev.y() + dy;
                    int z = prev.z() + dz;
                    if ((dx == 0 && dy == 0 && dz == 0) || !box.contains(x, y, z) || band(box, y) != band
                            || (j.alongX ? z : x) != row) {
                        continue;
                    }
                    Block b = left(j, x, y, z);
                    if (b == null || (y < box.yMax() && left(j, x, y + 1, z) != null)) {
                        continue;
                    }
                    BlockPos p = new BlockPos(x, y, z);
                    int at = j.planned.getOrDefault(p, Integer.MAX_VALUE);
                    if (at < besideAt && worker.digsFromHere(p, access, j.options.tools())) {
                        besideAt = at;
                        beside = b;
                    }
                }
            }
        }
        return beside != null ? beside : planned;
    }

    /** Which band of the box (see {@link #plan}) a height is in: 0 the top one. */
    private static int band(BlockBox box, int y) {
        return Math.floorDiv(box.yMax() - y, BAND);
    }

    /** Whether two blocks touch: one of the 26 around the other. */
    private static boolean touches(BlockPos a, BlockPos b) {
        return !a.equals(b) && Math.abs(a.x() - b.x()) <= 1 && Math.abs(a.y() - b.y()) <= 1
                && Math.abs(a.z() - b.z()) <= 1;
    }

    /** From the eyes to a block's middle. */
    private static double distance(Vec3 eye, BlockPos p) {
        double dx = p.x() + 0.5 - eye.x();
        double dy = p.y() + 0.5 - eye.y();
        double dz = p.z() + 0.5 - eye.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** A box is dug in bands this high, from its top: a body's height, what a player digs standing. */
    static final int BAND = 2;

    /**
     * The order a box is dug in, the way a player clears ground: bands {@link #BAND} high from the
     * top; in each, rows dug as a front - the row along the side the body comes from first, then
     * the one behind it, a row the one way and the next back (a snake), from the corner nearest
     * {@code from}; in each column of a row its blocks top down - a top, the one under it, then
     * the next column. The next band snakes back from where the one above ended. Fixed at the
     * start, so the body works along the rows instead of being drawn this way and that by
     * whichever block is nearest after each one - and the row dug is always the one it can get
     * at, nothing of the box in between.
     */
    static List<BlockPos> plan(BlockBox box, Vec3 from) {
        boolean alongX = rowsAlongX(box, from);
        int lMin = alongX ? box.xMin() : box.zMin();
        int lMax = alongX ? box.xMax() : box.zMax();
        int sMin = alongX ? box.zMin() : box.xMin();
        int sMax = alongX ? box.zMax() : box.xMax();
        double fl = from == null ? lMin : alongX ? from.x() : from.z();
        double fs = from == null ? sMin : alongX ? from.z() : from.x();
        boolean lUp = Math.abs(fl - (lMin + 0.5)) <= Math.abs(fl - (lMax + 0.5));
        boolean sUp = Math.abs(fs - (sMin + 0.5)) <= Math.abs(fs - (sMax + 0.5));
        List<BlockPos> out = new ArrayList<>();
        for (int top = box.yMax(); top >= box.yMin(); top -= BAND) {
            int bottom = Math.max(box.yMin(), top - BAND + 1);
            int sTo = sUp ? sMax : sMin;
            for (int sv = sUp ? sMin : sMax; ; sv += sUp ? 1 : -1) {
                int lTo = lUp ? lMax : lMin;
                for (int lv = lUp ? lMin : lMax; ; lv += lUp ? 1 : -1) {
                    for (int y = top; y >= bottom; y--) {
                        out.add(alongX ? new BlockPos(lv, y, sv) : new BlockPos(sv, y, lv));
                    }
                    if (lv == lTo) {
                        break;
                    }
                }
                lUp = !lUp;
                if (sv == sTo) {
                    break;
                }
            }
            sUp = !sUp;
        }
        return out;
    }

    /**
     * Whether the plan's rows run along x (else along z): the front goes away from the side the body
     * is out of the box at; from inside (or over it), rows along the box's longer side.
     */
    static boolean rowsAlongX(BlockBox box, Vec3 from) {
        double outX = from == null ? 0 : outside(from.x(), box.xMin(), box.xMax());
        double outZ = from == null ? 0 : outside(from.z(), box.zMin(), box.zMax());
        return outX == outZ ? box.xMax() - box.xMin() >= box.zMax() - box.zMin() : outZ > outX;
    }

    /** How far {@code v} is outside the cells {@code min..max} along one axis; 0 inside. */
    private static double outside(double v, int min, int max) {
        return v < min ? min - v : v > max + 1 ? v - (max + 1) : 0;
    }

    /** The box's block at a cell still to dig - known, not air, not left behind - or {@code null}. */
    private Block left(Job j, int x, int y, int z) {
        if (j.skipped.contains(new BlockPos(x, y, z)) || !world.known(x, y, z)) {
            return null;
        }
        Block b = world.blockAt(x, y, z);
        return b.isAir() ? null : b;
    }

    /** How many of the blocks it came for are still there. */
    private int left(Job j) {
        if (j.box == null) {
            return j.count > 0 ? Math.max(0, j.count - j.dug) : 0;
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

    /** The unit way from the eyes to a block's middle; {@code null} when the eyes are in it. */
    private static Vec3 along(Vec3 eye, BlockPos p) {
        double dx = p.x() + 0.5 - eye.x();
        double dy = p.y() + 0.5 - eye.y();
        double dz = p.z() + 0.5 - eye.z();
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return d < 1e-6 ? null : new Vec3(dx / d, dy / d, dz / d);
    }

    /**
     * What going on to a block costs: its middle's distance from the eyes, {@link #PER_RADIAN} for
     * each radian the head turns from {@code look} to it, less {@link #BESIDE} when it is one of the
     * 26 around the block broken last.
     */
    static double cost(Vec3 eye, Vec3 look, BlockPos last, BlockPos p) {
        double dx = p.x() + 0.5 - eye.x();
        double dy = p.y() + 0.5 - eye.y();
        double dz = p.z() + 0.5 - eye.z();
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double c = d;
        if (look != null && d > 1e-6) {
            // The turn of the head weighs in full for a block within reach; going on to one farther,
            // the walk there is what counts.
            double dot = (look.x() * dx + look.y() * dy + look.z() * dz) / d;
            c += PER_RADIAN * Math.min(1, TURN_NEAR / d) * Math.acos(Math.max(-1, Math.min(1, dot)));
        }
        if (last != null && !last.equals(p) && Math.abs(last.x() - p.x()) <= 1 && Math.abs(last.y() - p.y()) <= 1
                && Math.abs(last.z() - p.z()) <= 1) {
            c -= BESIDE;
        }
        return c;
    }

    private static String text(BlockPos p) {
        return p.x() + " " + p.y() + " " + p.z();
    }

    private static String progress(Job j, String doing) {
        String what = j.box != null ? "the selection"
                : j.count > 0 ? j.dug + " of " + j.count + " " + j.name : j.dug + " " + j.name;
        return "Mining " + what + ": " + j.broken.size() + " broken, " + j.skipped.size() + " left behind - " + doing
                + ".";
    }

    private static String describe(Job j, Result r) {
        String head = switch (r.status()) {
            case DONE -> "Done";
            case NOTHING -> j.box != null ? "Nothing to dig in the selection" : "No " + j.name + " about";
            case INTERRUPTED -> "Stopped";
            case NOT_AVAILABLE -> "Cannot move the player here";
        };
        if (r.status() == Status.NOTHING || r.status() == Status.NOT_AVAILABLE) {
            return head + ".";
        }
        int inTheWay = Math.max(0, r.broken() - r.dug());
        return String.format(Locale.ROOT, "%s: %d dug%s, %d left behind, %d drop(s) picked up%s%s.",
                head, r.dug(), inTheWay > 0 ? " and " + inTheWay + " in the way" : "", r.skipped(), r.picked(),
                r.unloaded() > 0 ? ", " + r.unloaded() + " item(s) into chests" : "",
                r.left() > 0 ? ", " + r.left() + " still to dig" : "");
    }
}
