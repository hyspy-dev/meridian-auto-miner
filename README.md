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
  - **The selection.** The box is dug out top layer first, from inside itself only.
  - **Blocks by name.** Up to *How many* blocks whose name holds the text (`Ore_Copper`), within
    *Within* blocks of where the player stood when it started, nearest first. A way the body's
    height is broken to a block in the rock, so its drop can be walked to.
- **Shovel for dirt, gravel and sand**, on by default. Soil is dug with a shovel when the hotbar
  has one. At the edge of a selection, core takes a tool that does not sweep past the edge: the
  shovel's arc would break the soil just outside the selection.
- **Pick up the drops**:
  - **Leave them**: they stay where they fell.
  - **After the clearing** (the default).
  - **Whenever enough lie about**: a round of picking up each time *this many* items lie within
    4 blocks of what was broken. The rest are picked up at the end.
- **Unload what was dug**. What the job dug is what the player carries more of than when it
  started: what was held before stays. It is unloaded when no slot is free in the hotbar, the
  storage and the backpack, and again at the end.
  - **Keep it** (the default).
  - **The nearest chest with room**: chests within 32 blocks, nearest first, until all of it is in.
  - **The chosen chest**: open it in the game, then press **Choose the chest I opened last**.
- **Mine** and **Stop**. The **Mining** line says how it goes.

The next block is dug the way a player digs, block by block: of the top layer left (or of the
blocks of the kind), the one cheapest to go on to. That is its distance from the eyes, plus 5 blocks
for each radian the head turns to it from the block broken last, less 1.5 for a block beside it. So
the block behind one just broken comes next, and the head goes round once, not back and forth.

A block the worker leaves is left behind and counted: next to lava, no place to break it from, or
it would not break. A block that comes back after it was broken, because it breaks into another,
is tried 3 times.

The drops picked up are those within 4 blocks of what was broken; the worker walks into each one's
cell.

A game's player's body is held for the whole job. These end it:

- a key that moves the player;
- Stop, here;
- Go or Stop in core's "Go to a point" panel.

## On the command channel

```
mine <conn> box <x y z x y z>                  -> {status, broken, dug, skipped, picked, left, unloaded}
mine <conn> selection                          -> the same, for the box dragged with the selection tool
mine <conn> blocks <name> [count] [radius]
mine <conn> cancel
mine <conn> set shovel on|off
mine <conn> set pickup none|end|every <n>
mine <conn> set unload off|nearest|chest <x y z>
```

## Not yet

- **Tunnels.** A block deeper in the rock than a tool reaches from open ground is left behind.
- **A full inventory with no chest to unload into.** Mining goes on, and its drops stay on the
  ground.
- **A pit deeper than the body climbs out of** (3 blocks). It is dug, and the body stays in it.
