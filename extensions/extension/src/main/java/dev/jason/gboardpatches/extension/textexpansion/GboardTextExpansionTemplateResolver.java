package dev.jason.gboardpatches.extension.textexpansion;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Resolves the small set of dynamic date/time placeholders supported by Text Expansion. */
public final class GboardTextExpansionTemplateResolver {
    private GboardTextExpansionTemplateResolver() {
    }

    /** Resolve placeholders using the device's current time and default timezone. */
    public static String resolve(String template) {
        return resolve(template, System.currentTimeMillis(), TimeZone.getDefault());
    }

    /** Package-visible deterministic overload used by unit tests. */
    static String resolve(String template, long epochMillis, TimeZone timeZone) {
        if (template == null || template.isEmpty() || template.indexOf('%') < 0) {
            return template;
        }

        TimeZone zone = timeZone == null ? TimeZone.getDefault() : timeZone;
        Calendar calendar = Calendar.getInstance(zone, Locale.US);
        calendar.setTimeInMillis(epochMillis);

        String result = template;
        result = result.replace("%yyyy%", four(calendar.get(Calendar.YEAR)));
        result = result.replace("%MM%", two(calendar.get(Calendar.MONTH) + 1));
        result = result.replace("%dd%", two(calendar.get(Calendar.DAY_OF_MONTH)));
        result = result.replace("%HH%", two(calendar.get(Calendar.HOUR_OF_DAY)));
        result = result.replace("%mm%", two(calendar.get(Calendar.MINUTE)));
        return result;
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static String four(int value) {
        if (value >= 1000) {
            return Integer.toString(value);
        }
        if (value >= 100) {
            return "0" + value;
        }
        if (value >= 10) {
            return "00" + value;
        }
        return "000" + value;
    }
}
