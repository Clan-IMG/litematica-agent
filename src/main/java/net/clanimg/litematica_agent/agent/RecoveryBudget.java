package net.clanimg.litematica_agent.agent;

/** Bounds recovery rounds until the build exceeds its previous verified progress. */
final class RecoveryBudget {
    private final int maximum;
    private int progress = -1;
    private int used;

    RecoveryBudget(int maximum) {
        this.maximum = maximum;
    }

    boolean available(int completed) {
        if (completed > this.progress) {
            this.progress = completed;
            this.used = 0;
        }
        return this.used < this.maximum;
    }

    int acquire() {
        if (this.used >= this.maximum) {
            throw new IllegalStateException("Recovery budget exhausted");
        }
        return ++this.used;
    }

    void reset() {
        this.progress = -1;
        this.used = 0;
    }
}
