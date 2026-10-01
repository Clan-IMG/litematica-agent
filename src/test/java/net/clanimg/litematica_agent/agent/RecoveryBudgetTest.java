package net.clanimg.litematica_agent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryBudgetTest {
    @Test
    void repeatedFailuresStopWithoutNetProgress() {
        RecoveryBudget budget = new RecoveryBudget(2);
        assertTrue(budget.available(100));
        assertEquals(1, budget.acquire());
        assertTrue(budget.available(100));
        assertEquals(2, budget.acquire());
        for (int tick = 0; tick < 100_000; tick++) {
            assertFalse(budget.available(100));
        }
        assertThrows(IllegalStateException.class, budget::acquire);
    }

    @Test
    void rebuildingRevertedBlocksCannotResetTheBudgetForever() {
        RecoveryBudget budget = new RecoveryBudget(2);
        assertTrue(budget.available(100));
        budget.acquire();
        budget.acquire();
        assertFalse(budget.available(98));
        assertFalse(budget.available(100));
        assertTrue(budget.available(101));
        assertEquals(1, budget.acquire());
    }

    @Test
    void explicitResumeAllowsFreshRecovery() {
        RecoveryBudget budget = new RecoveryBudget(2);
        assertTrue(budget.available(5));
        budget.acquire();
        budget.acquire();
        budget.reset();
        assertTrue(budget.available(5));
        assertEquals(1, budget.acquire());
    }
}
