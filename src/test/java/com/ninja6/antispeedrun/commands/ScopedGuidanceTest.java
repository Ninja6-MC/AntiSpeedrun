package com.ninja6.antispeedrun.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;

import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ninja6.antispeedrun.config.CreditedActions;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.CreditScope;
import com.ninja6.antispeedrun.listeners.ItemGateRules;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.IdleReminderRules;
import com.ninja6.antispeedrun.progression.IdleReminderRules.MilestoneProgress;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;
import com.ninja6.antispeedrun.progression.PersonalCreditAdvancementLookup;

class ScopedGuidanceTest {
    @ParameterizedTest
    @CsvSource({
            "false, ALL, false, false", "false, ALL, true, false",
            "false, PERMISSION, false, false", "false, PERMISSION, true, false",
            "true, ALL, false, true", "true, ALL, true, true",
            "true, PERMISSION, false, false", "true, PERMISSION, true, true"
    })
    void everySurfaceUsesTheEffectivePlayerScope(boolean enabled, CreditScope scope,
                                                boolean permission, boolean personal) {
        PluginConfig config = PluginConfig.defaults();
        PluginConfig.ItemProgression base = config.itemProgression();
        PluginConfig.ItemProgression settings = new PluginConfig.ItemProgression(
                base.enabled(), base.dropRecallEnabled(), base.gateDispensers(),
                base.gateNestedBundles(), base.feedbackCooldownSeconds(), base.rejectionMessage(),
                base.gatedItems(), enabled, true, scope);
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        assertEquals(PersonalCreditAdvancementLookup.REQUIRED_PERMISSION, args[0]);
                        return permission;
                    }
                    throw new AssertionError("Unexpected read: " + method.getName());
                });
        assertEquals(personal, PersonalCreditAdvancementLookup.applies(player, settings));
        PluginConfig.ItemProgression viewer = PersonalCreditAdvancementLookup.presentation(player, settings);
        assertEquals(personal, viewer.requirePersonalCredit());
        EligibilityResult missing = new EligibilityResult(false,
                List.of("minecraft:nether/obtain_blaze_rod"), List.of(), 0, 0, false);
        Milestone gate = new Milestone("test", "Test gate",
                new MilestoneRequirement(missing.missingAdvancements(), 0, 0));
        List<MilestoneProgress> progress = List.of(new MilestoneProgress(gate, missing));
        List<String> surfaces = List.of(
                String.join(" ", ProgressCardRenderer.card("Reader", progress,
                        ProgressCardRenderer.Style.FULL, viewer)),
                IdleReminderRules.nextStep(progress, viewer).orElseThrow(),
                IdleReminderRules.outstanding(missing, viewer).orElseThrow(),
                ItemGateRules.requirementText("", missing, viewer),
                AntiSpeedrunCommand.outstanding(missing, viewer));
        for (String text : surfaces) {
            assertEquals(personal, text.contains("kill a blaze yourself"), text);
            assertEquals(!personal, text.contains("minecraft:nether/obtain_blaze_rod"), text);
        }
        String book = String.join(" ", JourneyBookPages.pages(config, viewer));
        assertEquals(personal, book.toLowerCase(Locale.ROOT).contains("kill a blaze yourself"), book);
        assertEquals(!personal, book.contains("advancements.nether.obtain_blaze_rod.title"), book);
        assertEquals(personal, book.contains(CreditedActions.LOOT_NOTE), book);
        assertEquals("Custom hint", ItemGateRules.requirementText("Custom hint", missing, viewer));
        assertEquals("Custom refusal", ItemGateRules.rejection("Custom refusal", "Item", "Requirement"));
        assertTrue(JourneyBookPages.pages(config, viewer).size() <= JourneyBookPages.MAX_PAGES);
    }
}
