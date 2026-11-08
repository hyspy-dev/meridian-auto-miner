# meridian-navigation

Takes the player somewhere. It finds a way over the ground and walks it, move by move, through
core's `Movement`: walking, jumping up, pulling up onto ledges, dropping down. It is a Layer-2
module: meridian-api and meridian-core-api only, no packets.

It offers the `Navigator` service (`meridian.navigation.api`) for other modules: a farmer going to
its field, a builder going to its blueprint, a bot going anywhere.

```java
Navigator nav = ctx.services().require(Navigator.class);
nav.goTo(Goal.near(new BlockPos(120, 64, -40), 2))
   .thenAccept(r -> { if (r.arrived()) { /* work there */ } });
```

Movement is core's, and core moves only a player it plays the client of: a headless session. With
a game behind the session, `available()` is false.

## How a way is found

- **Search.** A* runs over the cells the player can stand in (`Movement.standAt`: a floor, room for
  the whole body, no fluid at the head).
- **Edges** are the body's moves, sized from `Movement.capabilities()`:
  - a walk, including a step up or down;
  - a jump up;
  - a pull-up onto a ledge;
  - a drop no longer than the body survives unhurt.
- **Diagonals** are walks only, and only where both corners leave room. The search never cuts past
  a block.
- **Costs** are seconds. The estimate is the straight distance at a sprint.
- **Budget.** A search stops after 30,000 cells or 1.5 s. When the budget runs out, or the world
  beyond is not sent yet, it gives the way to the cell it saw nearest the goal. The walker goes
  that far and looks again, the way Baritone plans in segments.

## How it is walked

- **One move at a time**, through `Movement.move`. Core checks every tick of a move against the
  blocks before it starts, so a move through a block does not start at all.
- **Looking again.** A blocked move, or a way that only got nearer, means a new search from where
  the feet are. A trip gives up after 12 such searches, or when a partial way gets no nearer than
  the one before.
- **One trip at a time.** A new `goTo` ends the trip under way.

## On the command channel

```
nav <conn> goto <x y z> [radius]   -> {status, at, moves, replans}
nav <conn> plan <x y z> [radius]   -> the moves, without moving
nav <conn> cancel
```

## Not yet

- **Swimming and climbing.** Ladders and vines (`BlockMovementSettings.isClimbable`) are not walked.
- **Sprinting**, and jumping over gaps.
- **Hanging from a ledge and shuffling along it.**
- **Doors.** Opening one on the way is a job for the walker and `InteractionControl`.
