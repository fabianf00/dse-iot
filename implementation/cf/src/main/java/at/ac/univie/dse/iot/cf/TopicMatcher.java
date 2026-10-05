package at.ac.univie.dse.iot.cf;

import java.util.regex.Pattern;

/**
 * Topic syntax and subscription pattern matching.
 *
 * <p>A topic consists of dot-separated segments of {@code [A-Za-z0-9_-]}, e.g.
 * {@code sensor.temperature}. A pattern is either a topic (exact match), a topic prefix followed
 * by {@code .*} (matches every topic with at least one more segment, e.g. {@code sensor.*}), or
 * {@code *} (matches every topic).
 */
final class TopicMatcher {

    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");
    private static final String WILDCARD = "*";
    private static final String WILDCARD_SUFFIX = ".*";

    private TopicMatcher() {
    }

    static String requireValidTopic(String topic) {
        if (topic == null || !TOPIC.matcher(topic).matches()) {
            throw new IllegalArgumentException(
                    "invalid topic '" + topic + "': expected dot-separated segments of [A-Za-z0-9_-]");
        }
        return topic;
    }

    static String requireValidPattern(String pattern) {
        if (WILDCARD.equals(pattern)) {
            return pattern;
        }
        if (pattern != null && pattern.endsWith(WILDCARD_SUFFIX)) {
            String prefix = pattern.substring(0, pattern.length() - WILDCARD_SUFFIX.length());
            if (TOPIC.matcher(prefix).matches()) {
                return pattern;
            }
        } else if (pattern != null && TOPIC.matcher(pattern).matches()) {
            return pattern;
        }
        throw new IllegalArgumentException(
                "invalid topic pattern '" + pattern + "': expected a topic, '<topic>.*' or '*'");
    }

    static boolean matches(String pattern, String topic) {
        if (WILDCARD.equals(pattern)) {
            return true;
        }
        if (pattern.endsWith(WILDCARD_SUFFIX)) {
            String prefixWithDot = pattern.substring(0, pattern.length() - 1);
            return topic.startsWith(prefixWithDot) && topic.length() > prefixWithDot.length();
        }
        return pattern.equals(topic);
    }
}
