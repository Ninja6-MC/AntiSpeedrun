package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.ConfigSection;
import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.Profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three shipped presets, read off the classpath and parsed by the real
 * {@link PluginConfig} code — the same technique {@code PluginConfigTest} uses on
 * {@code config.yml}, for the same reason: Bukkit is not on the test classpath, so the presets are
 * driven through the {@link ConfigSection} seam with genuine YAML.
 *
 * <p>This is what closes #57's fourth acceptance criterion. The naming drift it names —
 * {@code HARDCORE_SEEDRUN} against {@code HARDCORE} — is already gone from {@code main}, so the job
 * is to make the three names structurally unable to drift apart again: the resource path comes from
 * the enum constant ({@link ProfileApplier#resourcePath}), and each file must declare the profile
 * its own filename implies.
 */
class PresetProfileTest {

    private static PluginConfig preset(Profile profile) throws ConfigLoadException, IOException {
        String path = "/" + ProfileApplier.resourcePath(profile);
        try (InputStream in = PresetProfileTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "src/main/resources" + path + " must exist and be on the classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Object root = new Yaml().load(reader);
                assertTrue(root instanceof Map<?, ?>, path + " root must be a mapping");
                return PluginConfig.from(MapConfigSection.of((Map<?, ?>) root));
            }
        }
    }

    private static PluginConfig shipped() throws ConfigLoadException, IOException {
        try (InputStream in = PresetProfileTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "src/main/resources/config.yml must be on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return PluginConfig.from(MapConfigSection.of((Map<?, ?>) new Yaml().load(reader)));
            }
        }
    }

    @Test
    @DisplayName("every preset exists, parses, and declares the profile its filename implies")
    void namesAgree() throws Exception {
        for (Profile profile : ProfileApplier.applicable()) {
            assertEquals(profile, preset(profile).profile(),
                    ProfileApplier.resourcePath(profile) + " must declare profile: " + profile.name()
                            + ". The command signature, the profile: key and the resource filename "
                            + "have to say the same thing.");
        }
    }

    @Test
    @DisplayName("every preset parses without a single warning")
    void presetsAreClean() throws Exception {
        for (Profile profile : ProfileApplier.applicable()) {
            PluginConfig parsed = preset(profile);
            assertTrue(parsed.isClean(),
                    ProfileApplier.resourcePath(profile) + " parsed with warnings, so applying it "
                            + "would fill an operator's log the moment they used it: "
                            + parsed.warnings());
        }
    }

    @Test
    @DisplayName("SMP_STANDARD is exactly the shipped config.yml, so the default cannot drift")
    void smpStandardMatchesShipped() throws Exception {
        // Compared as parsed snapshots rather than as bytes: the two files carry different header
        // comments on purpose, and comments are not configuration.
        assertEquals(shipped(), preset(Profile.SMP_STANDARD));
    }

    @Test
    @DisplayName("CASUAL is looser than SMP_STANDARD and HARDCORE is stricter")
    void presetsDifferInTheDirectionTheyClaim() throws Exception {
        PluginConfig casual = preset(Profile.CASUAL);
        PluginConfig standard = preset(Profile.SMP_STANDARD);
        PluginConfig hardcore = preset(Profile.HARDCORE);

        // A preset whose name promises one thing and whose contents do another is worse than no
        // preset, so assert the direction rather than every individual key.
        assertTrue(casual.itemProgression().gatedItems().size()
                == standard.itemProgression().gatedItems().size(),
                "CASUAL keeps the tier table intact so re-enabling gating is one key, not a rewrite");
        assertEquals(false, casual.itemProgression().enabled());
        assertEquals(false, casual.trimProgression().enabled());
        assertEquals(true, standard.itemProgression().enabled());
        assertEquals(true, hardcore.itemProgression().enabled());

        assertTrue(casual.dimensionGates().theEnd().requireAdvancements().size()
                        < standard.dimensionGates().theEnd().requireAdvancements().size(),
                "CASUAL gates The End on fewer advancements than SMP_STANDARD");
        assertTrue(hardcore.dimensionGates().theEnd().requireAdvancements().size()
                        > standard.dimensionGates().theEnd().requireAdvancements().size(),
                "HARDCORE gates The End on more advancements than SMP_STANDARD");

        assertTrue(hardcore.antiCheese().maxSingleHitBossDamage()
                        < standard.antiCheese().maxSingleHitBossDamage(),
                "HARDCORE forces a longer boss fight than SMP_STANDARD");
        assertTrue(casual.antiCheese().maxSingleHitBossDamage()
                        > standard.antiCheese().maxSingleHitBossDamage(),
                "CASUAL permits a shorter boss fight than SMP_STANDARD");
    }

    @Test
    @DisplayName("no preset gates on tenure, because getFirstPlayed does not mean account age")
    void noPresetSetsAccountAge() throws Exception {
        // Finding R-15: require-account-age-days measures first join to THIS server, so a non-zero
        // value seals a dimension for the whole server on a fresh world. A preset must never turn
        // that on for an operator behind their back -- including HARDCORE.
        for (Profile profile : ProfileApplier.applicable()) {
            PluginConfig parsed = preset(profile);
            assertEquals(0, parsed.dimensionGates().nether().requireAccountAgeDays(),
                    profile + ": nether must not gate on tenure");
            assertEquals(0, parsed.dimensionGates().theEnd().requireAccountAgeDays(),
                    profile + ": the_end must not gate on tenure");
            parsed.itemProgression().gatedItems().forEach(tier ->
                    assertEquals(0, tier.requireAccountAgeDays(),
                            profile + ": tier " + tier.id() + " must not gate on tenure"));
        }
    }
}
