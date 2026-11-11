package forge.ai.simulation;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.ai.AiProfileUtil;
import forge.localinstance.properties.ForgeConstants;

/**
 * The four private profiles of the flat_mc seats (MTG_DeckMaker's tools/combo-ai/flat-mc/SPEC.md, section 4.1): the
 * text of the shipped Default.ai with lines appended, as the plan's make_profiles.py writes them into an engine tree
 * (Default.ai, LF, the lines joined by LF, LF), but LF only, whatever line ends the checkout gave Default.ai. They are
 * written once per JVM into a temporary directory beside a copy of every shipped profile, as FlatMonteCarloBenchmark
 * writes its arms, so a seat that names a shipped profile reads it as before.
 * <p>
 * An AITest class calls {@link #install()} in a {@code @BeforeMethod} of its own, which TestNG runs after AITest's
 * (that one loads the shipped profiles the first time any AITest class runs), and {@link #restore()} in its
 * {@code @AfterClass}.
 */
public final class FlatMcProfiles {
    /** The main-phase search alone: discovery, land choice and the opponent's-turn search off (spec 4.2). */
    private static final List<String> SEARCH_ALONE = List.of("FLAT_MC_DISCOVERY=false", "FLAT_MC_LAND_CHOICE=false",
            "FLAT_MC_REACTIVE=false");
    /** Each profile's lines, in the order they are appended. */
    private static final Map<String, List<String>> LINES = new LinkedHashMap<>();

    static {
        LINES.put("FlatSearch", SEARCH_ALONE);
        LINES.put("FlatSearchBudget", plus("FLAT_MC_DECISION_BUDGET_MS=18", "FLAT_MC_TURN_BUDGET_MS=80"));
        LINES.put("FlatSearchOff", plus("FLAT_MC_DECISION_BUDGET_MS=0"));
        LINES.put("FlatSearchTight", plus("FLAT_MC_DECISION_BUDGET_MS=2", "FLAT_MC_TURN_BUDGET_MS=6"));
    }

    private static File directory;

    private FlatMcProfiles() {
    }

    /** Loads the shipped profiles and the four, so that a seat may name any of them (LobbyPlayerAi.setAiProfile). */
    public static synchronized void install() {
        AiProfileUtil.loadAllProfiles(directory().getPath());
    }

    /** Loads the shipped profiles alone again, as AITest left them. */
    public static synchronized void restore() {
        AiProfileUtil.loadAllProfiles(ForgeConstants.AI_PROFILE_DIR);
    }

    /** The directory {@link #install()} loads, written the first time it is asked for. */
    static synchronized File directory() {
        if (directory == null) {
            directory = write();
        }
        return directory;
    }

    private static List<String> plus(final String... budget) {
        final List<String> lines = new ArrayList<>(SEARCH_ALONE);
        lines.addAll(List.of(budget));
        return List.copyOf(lines);
    }

    private static File write() {
        try {
            final Path out = Files.createTempDirectory("flatmc-profiles");
            deleteOnExit(out); // registered first, so deleted after the files in it
            final File[] shipped = new File(ForgeConstants.AI_PROFILE_DIR).listFiles((d, n) -> n.endsWith(".ai"));
            if (shipped == null || shipped.length == 0) {
                throw new IllegalStateException("no shipped profiles in " + ForgeConstants.AI_PROFILE_DIR);
            }
            for (final File f : shipped) {
                deleteOnExit(Files.copy(f.toPath(), out.resolve(f.getName()), StandardCopyOption.REPLACE_EXISTING));
            }
            final String base = Files.readString(Path.of(ForgeConstants.AI_PROFILE_DIR, "Default.ai"),
                    StandardCharsets.UTF_8).replaceAll("\r\n?", "\n");
            for (final Map.Entry<String, List<String>> e : LINES.entrySet()) {
                deleteOnExit(Files.writeString(out.resolve(e.getKey() + ".ai"),
                        base + "\n" + String.join("\n", e.getValue()) + "\n", StandardCharsets.UTF_8));
            }
            return out.toFile();
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot write the flat_mc profiles", e);
        }
    }

    private static void deleteOnExit(final Path file) {
        file.toFile().deleteOnExit();
    }
}
