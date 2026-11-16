package com.lixingchi.repose;

import java.time.*;

/** All deadlines are elapsed-realtime values; only calendar windows use wall time. */
public final class Policy {
    public static boolean inWindow(Settings c, Instant wall) {
        LocalDate day = wall.atZone(c.zone()).toLocalDate();
        return contains(c, day, wall) || contains(c, day.minusDays(1), wall);
    }
    private static boolean contains(Settings c, LocalDate day, Instant wall) {
        Instant start = day.atTime(LocalTime.parse(c.s("window_start"))).atZone(c.zone()).toInstant();
        return selectedDay(c, day) && !wall.isBefore(start) && wall.isBefore(ending(c, day));
    }
    private static Instant ending(Settings c, LocalDate day) {
        LocalTime start = LocalTime.parse(c.s("window_start")), end = LocalTime.parse(c.s("window_end"));
        return (end.isAfter(start) ? day : day.plusDays(1)).atTime(end).atZone(c.zone()).toInstant();
    }
    private static boolean selectedDay(Settings c, LocalDate day) {
        return ("," + c.s("window_days") + ",").contains("," + day.getDayOfWeek().getValue() + ",");
    }
    public static Instant nextOpening(Settings c, Instant wall) {
        LocalDate day = wall.atZone(c.zone()).toLocalDate();
        for (int i = 0; i <= 8; i++) {
            LocalDate d = day.plusDays(i);
            Instant start = d.atTime(LocalTime.parse(c.s("window_start"))).atZone(c.zone()).toInstant();
            if (selectedDay(c, d) && start.isAfter(wall) && start.isBefore(ending(c, d))) return start;
        }
        throw new IllegalStateException("invalid_window_days");
    }
    public static Instant skipThrough(Settings c, Instant wall) {
        LocalDate day = wall.atZone(c.zone()).toLocalDate();
        if (contains(c, day, wall)) return ending(c, day);
        if (contains(c, day.minusDays(1), wall)) return ending(c, day.minusDays(1));
        return ending(c, nextOpening(c, wall).atZone(c.zone()).toLocalDate());
    }
    public static String due(Settings c, long lastUnlock, long elapsed, Instant wall,
                             boolean locked, long snooze, long skip) {
        if (lastUnlock < 0 || elapsed < lastUnlock || elapsed < snooze) return "";
        long age = elapsed - lastUnlock;
        if (c.b("timed_enabled") && age >= c.n("timed_minutes") * 60000L
                && (!c.b("timed_wait_locked") || locked)) return "timed";
        if (c.b("window_enabled") && age >= c.n("window_minutes") * 60000L
                && elapsed >= skip && inWindow(c, wall) && (!c.b("window_wait_locked") || locked)) return "window";
        return "";
    }
    public static long next(Settings c, long last, long elapsed, Instant wall, boolean locked, long snooze, long skip) {
        if (last < 0) return Long.MAX_VALUE;
        long result = Long.MAX_VALUE;
        if (c.b("timed_enabled") && (!c.b("timed_wait_locked") || locked))
            result = Math.max(Math.max(last + c.n("timed_minutes") * 60000L, snooze), elapsed);
        if (c.b("window_enabled") && (!c.b("window_wait_locked") || locked)) {
            long candidate = Math.max(Math.max(last + c.n("window_minutes") * 60000L, snooze), Math.max(skip, elapsed));
            Instant target = wall.plusMillis(candidate - elapsed);
            if (!inWindow(c, target)) candidate += Duration.between(target, nextOpening(c, target)).toMillis();
            result = Math.min(result, candidate);
        }
        return result;
    }
    public static boolean unlockAccepted(String scope, String authentication) {
        return scope.equals("any") || authentication.equals("credential")
                || scope.equals("authenticated") && authentication.equals("biometric");
    }
    public static boolean biometricAccepted(Settings c, String kind) {
        return c.s("fail_biometrics").equals("both") || c.s("fail_biometrics").equals(kind);
    }
    private Policy() { }
}
