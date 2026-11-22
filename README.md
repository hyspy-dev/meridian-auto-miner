# meridian-auto-miner

Digs, with the player's own character or a headless one. It decides what to dig and in what
order. Getting at each block, breaking it and picking up the drops is core's `Worker`, which walks
with core's `Navigator` (see [meridian-core/docs/navigation.md](../meridian-core/docs/navigation.md)).

It is a Layer-2 module: meridian-api and meridian-core-api only, no packets. The repository folder
is still named `meridian-navigation`: the navigation it once held is core's now.

## In the proxy's window: Mine

- **Selection tool in slot 1** puts the game's own selection tool in the first hotbar slot. This
  needs core's `BuilderSelection`, on 0.6 and later. The **Selection** line shows the box dragged
  with it.
- **What to mine**:
  - **The selection.** The box is dug out from inside itself only, by a plan made at the start:
    bands 2 blocks high from the top; in each, rows dug as a front - first the row along the side
    the body comes from, then the one behind it, each row the other way (a snake), from the corner
    nearest the body; in each column its top, then the block under it, then the next column. The
    next band snakes back from where the one above ended. Within a row the body goes on from the block
    broken last; a new row is begun at the end of what is left of it nearer that block - a row half
    dug already is not walked to its far end. The selection is kept in the module's data folder
    (`selection.txt`): with none dragged this session, **Mine** digs the one from before.
  - **Blocks by name.** *How many to dig* blocks whose name holds the text (`Ore_Copper`); -1, the
    default, digs on as long as there are any. Each is looked for round where the body is then,
    48 blocks about: about what the game has loaded. A way the body's height is broken to a block in
    the rock, so its drop can be walked to.
- **Shovel for dirt, gravel and sand**, on by default. Soil is dug with a shovel when the hotbar
  has one. At the edge of a selection, core takes a tool that does not sweep past the edge: the
  shovel's arc would break the soil just outside the selection.
- **Pick up the drops**:
  - **Leave them**: they stay where they fell.
  - **After the clearing** (the default).
  - **Whenever enough lie about**: a round of picking up each time *this many* items lie within
    4 blocks of what was broken. The rest are picked up at the end.
  - **Everything it dropped, wherever it went**: at the end, every item the job dropped, wherever
    it went since: rolled off, fell down a hole, floated away. An item is the job's when it showed up
    by a block the job broke after the job started (`EntityTracker.firstSeenAt`); items that lay
    there before are left.
- **Unload what was dug**. What the job dug is what the player carries more of than when it
  started: what was held before stays. It is unloaded when no slot is free in the hotbar, the
  storage and the backpack, and again at the end.
  - **Keep it** (the default).
  - **The nearest chest with room**: chests within 32 blocks, nearest first, until all of it is in.
  - **The chosen chest**: open it in the game, then press **Choose the chest I opened last**.
- **Repair a broken tool with a kit from the hotbar** (off): before each block, a tool in the hotbar
  with under 2% of its durability left is repaired with a `Repair_Kit` from the hotbar, as a player
  does (core's `InteractionControl.repair`).
- **Outline the block being dug**, on by default: the block the job goes to dig now is outlined in
  orange, through walls (core's `DebugRender`).
- **Disconnect after done** (off): the session leaves the server, as a player quitting does (core's
  `Life.leave`), once a job has come to its own end - everything dug, nothing there to dig, or no
  room left in the inventory nor in any chest. A job that was stopped (Stop, another job, a key that
  moves the player) leaves the session in; so does a death - core goes back for the things, then
  leaves by itself. The tick is asked at the job's end: set while it digs, it holds for that job.
- **Mine** and **Stop**. The **Mining** line says how it goes.

Blocks by name go the way a player digs, block by block: of the blocks of the kind, the one
cheapest to go on to. That is its distance from the eyes, plus 5 blocks
for each radian the head turns to it from the block broken last, less 1.5 for a block beside it. So
the block behind one just broken comes next, and the head goes round once, not back and forth.

A block the worker leaves is left behind and counted: next to lava, no place to break it from, or
it would not break. A block that comes back after it was broken, because it breaks into another,
is tried 3 times.

The drops picked up are those within 4 blocks of what was broken; the worker walks into each one's
cell, or, where the body cannot stand there, to the nearest place within 1.5 of it. Before the end's
round, the last block's drop is waited for (up to 0.8 s): the server shows it a moment after the
block goes.

A game's player's body is held for the whole job. These end it:

- a key that moves the player;
- Stop, here;
- Go or Stop in core's "Go to a point" panel.

## On the command channel

```
mine <conn> box <x y z x y z>                  -> {status, broken, dug, skipped, picked, left, unloaded}
mine <conn> selection                          -> the same, for the box dragged with the selection tool
mine <conn> blocks <name> [count]              -> count -1 (the default): as long as there are any
mine <conn> cancel
mine <conn> set shovel on|off
mine <conn> set pickup none|end|every <n>|all
mine <conn> set unload off|nearest|chest <x y z>
mine <conn> set disconnect on|off              -> out of the server once a job has ended by itself
```

## Not yet

- **Long tunnels.** A tunnel to a block is 48 moves at most; a block farther in is left behind.
- **A full inventory with no chest to unload into.** Mining goes on, and its drops stay on the
  ground.
- **A pit deeper than the body climbs out of** (3 blocks). It is dug, and the body stays in it.
