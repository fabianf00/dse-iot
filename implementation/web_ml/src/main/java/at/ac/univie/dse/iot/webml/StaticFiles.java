package at.ac.univie.dse.iot.webml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The UI files from the classpath directory {@code web/}. Only plain paths of known file types
 * are served: no {@code ..}, no hidden files, no directories, so nothing outside {@code web/}
 * can be read.
 */
final class StaticFiles {

    /** A file to send. */
    record StaticFile(byte[] content, String contentType) {
    }

    private static final String ROOT = "web/";
    private static final String INDEX = "index.html";
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "md", "text/markdown; charset=utf-8");

    private final ClassLoader classLoader;

    StaticFiles() {
        this(StaticFiles.class.getClassLoader());
    }

    StaticFiles(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * The file for a decoded request path such as {@code /} or {@code /vendor/chart.umd.min.js}.
     *
     * @return empty if the path is invalid or there is no such file
     */
    Optional<StaticFile> find(String path) {
        if (path == null || !path.startsWith("/")) {
            return Optional.empty();
        }
        String relative = path.equals("/") ? INDEX : path.substring(1);
        String contentType = contentType(relative);
        if (contentType == null || !isPlainPath(relative)) {
            return Optional.empty();
        }
        try (InputStream in = classLoader.getResourceAsStream(ROOT + relative)) {
            return in == null ? Optional.empty() : Optional.of(new StaticFile(in.readAllBytes(), contentType));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + ROOT + relative, e);
        }
    }

    private static boolean isPlainPath(String relative) {
        for (String segment : relative.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches() || segment.contains("..")) {
                return false;
            }
        }
        return true;
    }

    private static String contentType(String relative) {
        int dot = relative.lastIndexOf('.');
        return dot < 0 ? null : CONTENT_TYPES.get(relative.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
