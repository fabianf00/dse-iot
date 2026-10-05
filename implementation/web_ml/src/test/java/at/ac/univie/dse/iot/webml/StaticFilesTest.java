package at.ac.univie.dse.iot.webml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.webml.StaticFiles.StaticFile;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StaticFilesTest {

    private final StaticFiles files = new StaticFiles();

    @ParameterizedTest
    @CsvSource({
            "/, text/html; charset=utf-8",
            "/index.html, text/html; charset=utf-8",
            "/app.js, text/javascript; charset=utf-8",
            "/style.css, text/css; charset=utf-8",
            "/vendor/chart.umd.min.js, text/javascript; charset=utf-8",
            "/sub/test.css, text/css; charset=utf-8"})
    void servesTheUiFilesWithTheirContentType(String path, String contentType) {
        StaticFile file = files.find(path).orElseThrow();

        assertEquals(contentType, file.contentType());
        assertTrue(file.content().length > 0);
    }

    @Test
    void rootIsTheIndexPage() {
        String index = new String(files.find("/").orElseThrow().content(), StandardCharsets.UTF_8);

        assertTrue(index.contains("<script type=\"module\" src=\"app.js\">"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/missing.js",            // no such file
            "/vendor",                // directory
            "/vendor/",               // directory
            "/README",                // no known file type
            "/.hidden.css",           // hidden file
            "/../pom.xml",            // outside web/
            "/../web/app.js",
            "/vendor/../app.js",
            "/./app.js",
            "/vendor//chart.umd.min.js",
            "/at/ac/univie/dse/iot/webml/WebMlService.class",
            "/logback.xml",
            "//etc/passwd.css",
            "/app.js\\..\\x.css",
            "app.js",                 // not absolute
            ""})
    void refusesEverythingElse(String path) {
        assertTrue(files.find(path).isEmpty(), path);
    }

    @Test
    void nullPathIsNotFound() {
        assertTrue(files.find(null).isEmpty());
    }
}
