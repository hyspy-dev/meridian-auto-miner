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

Movement is core's, and core moves only a player it plays the client of: a headless session. With
a game behind the session, `available()` is false.

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

## On the command channel

```
nav <conn> goto <x y z> [radius]   -> {status, at, moves, replans}
nav <conn> plan <x y z> [radius]   -> the moves (kind, cell, gait), without moving
nav <conn> cancel
```

## Not yet

- **Gliding** and **riding**.
- **Hanging along a ledge** as a way of its own (the body can; the search does not plan it).
- **Breaking or building** through what is in the way.
