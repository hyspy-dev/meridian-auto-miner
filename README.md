# meridian-navigation

Takes the player somewhere. It finds a way and goes it, move by move, through core's `Movement`:
walking, jumping up, pulling up onto ledges, dropping down, leaping gaps, swimming, climbing ladders
and vines, flying where the player may, opening doors on the way. It is a Layer-2 module:
meridian-api and meridian-core-api only, no packets.

It offers the `Navigator` service (`meridian.navigation.api`) for other modules: a farmer going to
its field, a builder going to its blueprint, a bot going anywhere.

```java
Navigator nav = ctx.services().require(Navigator.class);
nav.goTo(Goal.near(new BlockPos(120, 64, -40), 2))
   .thenAccept(r -> { if (r.arrived()) { /* work there */ } });
```

Movement is core's. A headless player's body is core's all the time. A game's player is taken
from the game for the trip (`Movement.take`) and given back at its end. The player takes it back
at once with a key that moves them; the server's teleport or a death ends the trip as well.

## How a way is found

- **Places.** A* runs over where the body can be: standing on the ground (`Movement.standAt`),
  swimming in a fluid, holding on to something climbable, in the air where it may fly
  (`Movement.cell`).
- **Edges** are the body's moves, sized from `Movement.capabilities()`, which core works out with the
  body's own physics:
  - a walk, a step up or down included;
  - a jump up, a pull-up onto a ledge;
  - a drop the body survives unhurt, and any height into deep water;
  - a running or sprinting jump over a gap (level, a block up or down);
  - into, through and out of water;
  - onto, along and off a ladder, a vine, a rope;
  - a flight.
- **Doors.** A shut door on the level is a way through, opened first.
- **Diagonals** are walks and swims only, and only where both corners leave room.
- **Costs** are seconds. A jump up and a pull-up go by their length (a block across and the rise, at
  a run), not by their time, so a shorter way over a ledge wins over a longer one round it. The
  estimate is the straight distance at the body's fastest.
- **Budget.** A search stops after 40,000 places or 1.5 s. When the budget runs out, or the world
  beyond is not sent yet, it gives the way to the place it saw nearest the goal. The walker goes
  that far and looks again, the way Baritone plans in segments.

## How it is gone

- **Played on a copy first.** The whole way, up to its first door, is played on a copy of the body
  (`Movement.predict`). A move that would not arrive, or would land hard enough to hurt, is banned and
  the way found again.
- **All at once.** The checked way goes to `Movement` as one queue of moves, so the body runs through
  its points without stopping.
- **Doors.** At a door the walker turns to it, uses it (`InteractionControl.useOnBlock`), waits for
  the server to show it open, and looks again from there. A door that does not open is gone round.
- **Looking again.** A move that goes wrong, or a way that only got nearer, means a new search from
  where the body is. A trip gives up after 12 such searches, or when a partial way gets no nearer than
  the one before.
- **One trip at a time.** A new `goTo` ends the trip under way.

## Mining

The proxy window's **Mine** section digs, with the player's own character or a headless one:

- **The selection.** "Selection tool in slot 1" puts the game's own selection tool in the first
  hotbar slot (core's `BuilderSelection`, 0.6 and later). The box dragged with it is dug out, top
  layer first, and only inside itself.
- **Blocks by name.** Up to *How many* blocks whose name holds the text (`Ore_Copper`), within
  *Within* blocks of where the player stood when it started, nearest first.

Per block:

- **The tool.** The hotbar's tool that breaks it best (`Player.bestToolSlot`, the server's own
  rule: a spec of the block's gather type, of at least its quality, the strongest).
- **Where from.** A place within that tool's dig reach (`InteractionControl.digReach`): a pickaxe
  breaks the block it is aimed at, up to 5 away (4.5 is used); a shovel's sweep reaches 2.5. The
  body stays where it is when it can, else walks there. It never stands on the block, nor on one in
  the way.
- **In the way.** For a block in the rock, a way in the body's height is broken from that place up
  to the block, so its drop can be walked to; then whatever is still on the line from the eyes.
- **Left.** A block next to lava, one with no place to break it from (3 places tried), or one that
  would not break in 30 swings, is left behind.

At the end the drops around what was broken are walked over, nearest first; the server takes one
within 1.75 of the feet. A game's player is held for the whole job, and a key that moves them ends
it, as Stop and Go do.

## On the command channel

```
nav <conn> goto <x y z> [radius]   -> {status, at, moves, replans}
nav <conn> plan <x y z> [radius]   -> the moves (kind, cell, gait), without moving
nav <conn> mine box <x y z x y z>  -> {status, broken, dug, skipped, picked, left}
nav <conn> mine selection          -> the same, for the box dragged with the selection tool
nav <conn> mine blocks <name> [count] [radius]
nav <conn> cancel                  -> ends a trip or a mining job
```

## Not yet

- **Gliding** and **riding**.
- **Hanging along a ledge** as a way of its own (the body can; the search does not plan it).
- **Breaking or building** through what is in the way of a trip: a way is found over the ground as
  it is. Mining breaks only the blocks it came for and the way in to them.
- **Tunnels.** A block deeper in the rock than a tool reaches from open ground is left behind.
- **A full inventory**: mining goes on, its drops stay on the ground.
