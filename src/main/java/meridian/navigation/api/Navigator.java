package meridian.navigation.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import meridian.core.api.BlockPos;
import meridian.core.api.Movement;

/**
 * Takes the player somewhere: finds a way and goes it, move by move, through core's
 * {@link Movement} - walking, jumping up, pulling up onto ledges, dropping down, leaping gaps,
 * swimming, climbing ladders and vines, flying where the player may.
 *
 * <p>A way is found over what core knows of the world. Where the world is not sent yet, the
 * navigator walks as far towards the goal as it can see and looks again from there; where a move
 * turns out blocked (the world changed), it looks again from where the player stands. It gives up
 * when no way is left, or after too many tries.
 *
 * <p>One trip at a time: a new {@link #goTo} ends the one under way. The service is offered by the
 * {@code meridian-navigation} module; other modules take it from the service registry.
 */
public interface Navigator {

    /**
     * Whether the player can be moved here: a headless one, or a game's player, whose body core
     * takes from the game for a trip ({@link Movement#take}).
     */
    boolean available();

    /**
     * Goes to {@code goal}; completes when there, or when no way is left.
     *
     * <p>A game's player's body is taken from the game for the trip and given back at its end -
     * unless it was held already, and then whoever took it gives it back. The player taking it
     * back (a key that moves them, the server's teleport, a death) ends the trip {@code INTERRUPTED}.
     */
    CompletableFuture<NavResult> goTo(Goal goal);

    /** The way to {@code goal} as it is found now, without moving - for a look before going. */
    List<Step> plan(Goal goal);

    /** Ends the trip under way ({@code INTERRUPTED}). */
    void cancel();

    /**
     * One move of a way: of a kind, onto a cell, the feet at that height, at a gait - through the
     * door at {@code door} when it is not {@code null}, which is opened first.
     */
    record Step(Movement.Kind kind, int x, int y, int z, double feet, Movement.Gait gait, BlockPos door) {}
}
