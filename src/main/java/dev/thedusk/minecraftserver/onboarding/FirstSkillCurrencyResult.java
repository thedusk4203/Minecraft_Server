package dev.thedusk.minecraftserver.onboarding;

public record FirstSkillCurrencyResult(
        boolean firstSkillRecorded,
        boolean currencyGranted,
        long balanceAfter,
        String skillId,
        int skillLevel
) {
}
