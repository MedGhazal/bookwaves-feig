package de.bookwaves.sync;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * The tag timings an operator can tune.
 *
 * <p>Configuration carries milliseconds; the reader stores a count of its own steps, and
 * the step differs per parameter.
 */
public enum TagTiming {

    /** How long before the same tag is reported again. */
    TRANSPONDER_VALID_TIME("transponderValidTime", Duration.ofMillis(100), Duration.ofSeconds(1)),

    /** How long after a read the reader resets a tag's persistence flags. */
    PERSISTENCE_RESET_TIME("persistenceResetTime", Duration.ofMillis(5), Duration.ofSeconds(1));

    /** The configured value asking the reader never to repeat or reset. */
    public static final String NEVER = "never";

    /** The step count the reader reads as never. */
    private static final long NEVER_STEPS = 65535;

    private final String key;
    private final Duration step;
    private final Duration fallback;

    TagTiming(String key, Duration step, Duration fallback) {
        this.key = key;
        this.step = step;
        this.fallback = fallback;
    }

    /** The key this parameter is set under in {@code config.yaml}. */
    public String key() {
        return key;
    }

    /** Whether this parameter can store {@code configured}, an unset value included. */
    public boolean isSupported(String configured) {
        return parse(configured).isPresent();
    }

    /**
     * The step count the reader stores for milliseconds, {@link #NEVER}, or an unset value.
     *
     * @throws IllegalArgumentException if this parameter cannot store the value
     */
    public long steps(String configured) {
        return parse(configured).orElseThrow(() -> new IllegalArgumentException(
            key + " " + configured + " is not supported; accepted are " + supportedValues()));
    }

    /** The values this parameter accepts. */
    public String supportedValues() {
        return "0.." + (NEVER_STEPS - 1) * step.toMillis() + " ms in whole steps of "
            + step.toMillis() + " ms, or '" + NEVER + "'";
    }

    private OptionalLong parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return OptionalLong.of(fallback.toMillis() / step.toMillis());
        }
        String value = configured.trim();
        if (NEVER.equalsIgnoreCase(value)) {
            return OptionalLong.of(NEVER_STEPS);
        }
        long millis;
        try {
            millis = Long.parseLong(value);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
        if (millis < 0 || millis % step.toMillis() != 0) {
            return OptionalLong.empty();
        }
        long steps = millis / step.toMillis();
        return steps < NEVER_STEPS ? OptionalLong.of(steps) : OptionalLong.empty();
    }
}
