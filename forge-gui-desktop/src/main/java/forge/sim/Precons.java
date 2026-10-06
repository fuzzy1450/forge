package forge.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import forge.localinstance.properties.ForgeConstants;

/** The Commander precons Forge ships, which the determinism battery plays. */
public final class Precons {
    private Precons() { }

    /** Every {@code .dck} under the quest precon directory with a {@code [Commander]} section,
     *  sorted by file name: P1..Pn of the battery (spec 7.1). Read as ISO-8859-1 so a stray
     *  non-UTF-8 byte in a deck file cannot silently drop it from the list. */
    public static List<Path> commander() {
        Path dir = Paths.get(ForgeConstants.QUEST_PRECON_DIR);
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".dck"))
                    .filter(Precons::isCommander)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + dir.toAbsolutePath(), e);
        }
    }

    private static boolean isCommander(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1).contains("[Commander]");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }
}
