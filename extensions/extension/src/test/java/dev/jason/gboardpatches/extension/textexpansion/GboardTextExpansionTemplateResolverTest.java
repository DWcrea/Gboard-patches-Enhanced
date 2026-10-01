package dev.jason.gboardpatches.extension.textexpansion;

import org.junit.Assert;
import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public final class GboardTextExpansionTemplateResolverTest {
    @Test
    public void resolvesSupportedDateAndTimePlaceholders() {
        TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
        Calendar calendar = Calendar.getInstance(zone, Locale.US);
        calendar.clear();
        calendar.set(2026, Calendar.OCTOBER, 1, 20, 46, 30);

        Assert.assertEquals(
                "2026-10-01",
                GboardTextExpansionTemplateResolver.resolve(
                        "%yyyy%-%MM%-%dd%", calendar.getTimeInMillis(), zone));
        Assert.assertEquals(
                "20:46",
                GboardTextExpansionTemplateResolver.resolve(
                        "%HH%:%mm%", calendar.getTimeInMillis(), zone));
        Assert.assertEquals(
                "日期2026-10-01 时间20:46",
                GboardTextExpansionTemplateResolver.resolve(
                        "日期%yyyy%-%MM%-%dd% 时间%HH%:%mm%",
                        calendar.getTimeInMillis(), zone));
    }

    @Test
    public void ordinaryTextAndUnknownPlaceholdersRemainUnchanged() {
        TimeZone zone = TimeZone.getTimeZone("UTC");
        Assert.assertEquals(
                "13800138000",
                GboardTextExpansionTemplateResolver.resolve("13800138000", 0L, zone));
        Assert.assertEquals(
                "hello %ss%",
                GboardTextExpansionTemplateResolver.resolve("hello %ss%", 0L, zone));
        Assert.assertNull(GboardTextExpansionTemplateResolver.resolve(null, 0L, zone));
        Assert.assertEquals("", GboardTextExpansionTemplateResolver.resolve("", 0L, zone));
    }

    @Test
    public void zeroPadsSingleDigitDateAndTimeFields() {
        TimeZone zone = TimeZone.getTimeZone("UTC");
        Calendar calendar = Calendar.getInstance(zone, Locale.US);
        calendar.clear();
        calendar.set(2026, Calendar.JANUARY, 2, 3, 4, 0);

        Assert.assertEquals(
                "2026-01-02 03:04",
                GboardTextExpansionTemplateResolver.resolve(
                        "%yyyy%-%MM%-%dd% %HH%:%mm%",
                        calendar.getTimeInMillis(), zone));
    }
}
