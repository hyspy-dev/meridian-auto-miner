package meridian.autominer;

import java.util.function.Predicate;
import meridian.api.module.ModuleContext;
import meridian.core.api.Life;

/**
 * Leaving the server through core ({@link Life#leave}). A class of its own: with a core that has no
 * {@code Life}, or one that cannot leave, only this fails to load - the miner digs as before.
 */
final class Leaver {

    private Leaver() {
    }

    /** Leaves, given why in words; {@code null} where core has no life service. */
    static Predicate<String> of(ModuleContext ctx) {
        Life life = ctx.services().get(Life.class).orElse(null);
        return life == null ? null : life::leave;
    }
}
