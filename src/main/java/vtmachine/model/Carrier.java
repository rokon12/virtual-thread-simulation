package vtmachine.model;

/** Mutable carrier-thread state owned exclusively by {@link Sim}. */
public final class Carrier {
    private final int index;
    Vt mounted;
    double pinT;
    double heat;

    Carrier(int index) {
        this.index = index;
    }

    public int index() { return index; }
    public Vt mounted() { return mounted; }
    public double pinT() { return pinT; }
    public double heat() { return heat; }
    public boolean pinned() { return pinT > 0; }
}
