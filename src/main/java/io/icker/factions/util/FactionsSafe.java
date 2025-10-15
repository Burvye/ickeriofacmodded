package io.icker.factions.util;

import io.icker.factions.api.persistents.Claim;
import io.icker.factions.api.persistents.Faction;
import org.jetbrains.annotations.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class FactionsSafe {
    private FactionsSafe() {}

    public static int claimCount(@Nullable Faction f) {
        return (f == null) ? 0 : f.getClaims().size();
    }

    /** Null-safe; returns an empty list when f is null */
    public static List<Claim> claims(@Nullable Faction f) {
        return (f == null) ? Collections.emptyList() : f.getClaims();
    }

    public static int power(@Nullable Faction f) {
        return (f == null) ? 0 : f.getPower();
    }

    /** Your exact line, but safe */
    public static int requiredPowerToClaim(@Nullable Faction f, int claimWeight) {
        return (claimCount(f) + 1) * claimWeight;
    }

    /** If you often test “has enough power for current claims” */
    public static boolean hasSufficientClaimPower(@Nullable Faction f, int claimWeight) {
        return power(f) >= (claimCount(f) * claimWeight);
    }
}