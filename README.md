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
- **Mine** and **Stop**. The **Mining** line says how it goes.

A block the worker leaves is left behind and counted: next to lava, no place to break it from, or
it would not break. A block that comes back after it was broken, because it breaks into another,
is tried 3 times.

At the end, the drops within 4 blocks of what was broken are picked up.

A game's player's body is held for the whole job. These end it:

- a key that moves the player;
- Stop, here;
- Go or Stop in core's "Go to a point" panel.

## On the command channel

```
mine <conn> box <x y z x y z>                  -> {status, broken, dug, skipped, picked, left}
mine <conn> selection                          -> the same, for the box dragged with the selection tool
mine <conn> blocks <name> [count] [radius]
mine <conn> cancel
```

## Not yet

- **Tunnels.** A block deeper in the rock than a tool reaches from open ground is left behind.
- **A full inventory.** Mining goes on, and its drops stay on the ground.
- **A pit deeper than the body climbs out of** (3 blocks). It is dug, and the body stays in it.
