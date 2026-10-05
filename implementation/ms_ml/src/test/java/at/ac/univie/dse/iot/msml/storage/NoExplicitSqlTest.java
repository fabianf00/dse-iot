package at.ac.univie.dse.iot.msml.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the "no explicit SQL" requirement: the main code of MS ML must not contain SQL or JPQL
 * strings, native queries or named queries. All database access goes through the ORM.
 */
class NoExplicitSqlTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\"\\s*(SELECT|INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|DELETE\\s+FROM|CREATE\\s+TABLE"
                    + "|ALTER\\s+TABLE|DROP\\s+TABLE|FROM\\s+\\w+\\s+WHERE)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("createQuery\\(\\s*\""),
            Pattern.compile("createNativeQuery\\("),
            Pattern.compile("@NamedQuery|@NamedNativeQuery|@SqlResultSetMapping"),
            Pattern.compile("java\\.sql\\.(Statement|PreparedStatement)|\\bjava\\.sql\\.Connection\\b"));

    @Test
    void mainCodeContainsNoSqlOrJpqlStrings() throws IOException {
        List<String> findings = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    for (Pattern pattern : FORBIDDEN) {
                        if (pattern.matcher(lines.get(i)).find()) {
                            findings.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), findings, "explicit SQL/JPQL found");
    }

    @Test
    void guardRecognizesForbiddenCode() {
        assertTrue(FORBIDDEN.get(0).matcher("String q = \"SELECT r FROM MlResultEntity r\";").find());
        assertTrue(FORBIDDEN.get(0).matcher("\"delete from ml_comment\"").find());
        assertTrue(FORBIDDEN.get(1).matcher("em.createQuery(\"from AnomalyEntity\")").find());
        assertTrue(FORBIDDEN.get(2).matcher("em.createNativeQuery(sql)").find());
        assertFalse(FORBIDDEN.get(1).matcher("em.createQuery(criteria)").find(), "Criteria queries are allowed");
    }
}
