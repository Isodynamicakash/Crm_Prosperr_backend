package com.prosperr.crm.ai.application;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The coach's review is built from the numbers only: pace, team comparison and actions. */
class CoachPointsTest {
    private Map<String, Object> facts(Map<String, Object> me, Map<String, Object> calls, Map<String, Object> avg, Map<String, Object> now) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("bdr", "Paramita");
        f.put("period", Map.of("from", "2026-11-01", "to", "2026-11-24", "running", true, "shareElapsed", 0.8, "workingDaysLeft", 5));
        f.put("me", me); f.put("calls", calls); f.put("teamAverage", avg);
        f.put("rankByL1Done", 2L); f.put("bdrsRanked", 8L);
        f.put("hours", List.of(Map.of("hour", 16, "calls", 20L, "connectRate", 45)));
        f.put("sql", Map.of("submitted", 4L, "approved", 2L, "rejected", 2L, "topRejection", "Outside ICP cities"));
        f.put("noShows", 1L);
        f.put("now", now);
        return f;
    }

    @Test
    void behindPaceWithWeakCallingGetsConcreteActions() {
        List<String> p = AssistantService.coachPoints(facts(
                Map.of("l1Done", 8L, "l1DoneTarget", 20.0, "l1Booked", 10L, "l1BookedTarget", 0.0),
                Map.of("calls", 150L, "callsPerDay", 8.5, "connectRate", 20, "noAnswerShare", 70, "seniorShare", 50, "connectsPerL1", 6.0),
                Map.of("callsPerDay", 14.0, "connectRate", 32, "connectsPerL1", 5.5, "bdrsCompared", 7),
                Map.of("overdueFollowUps", 3L, "neverCalled", 4L, "staleAccounts", 2L)));
        String all = String.join("\n", p);
        assertTrue(all.contains("L1 meetings done: 8 of 20, behind pace (about 16 expected by now). 12 more needed in 5 working days, about 2.4 a day."), all);
        assertTrue(all.contains("3 follow-ups are overdue"), all);
        assertTrue(all.contains("8.5 calls a working day; the team average is 14"), all);
        assertTrue(all.contains("best hour is 4 pm to 5 pm, when 45%"), all);
        assertTrue(all.contains("Most calls go unanswered (70%)"), all);
        assertEquals(3, p.stream().filter(x -> x.startsWith("To improve")).count(), "at most three things to fix");
        assertTrue(p.get(p.size() - 1).startsWith("Focus this week: clear them first"), p.get(p.size() - 1));
        assertTrue(all.contains("10 L1 meetings booked (no target set)"));
    }

    @Test
    void strongMonthIsPraisedAndNothingIsInvented() {
        Map<String, Object> f = facts(
                Map.of("l1Done", 21L, "l1DoneTarget", 20.0),
                Map.of("calls", 400L, "callsPerDay", 22.0, "connectRate", 40, "noAnswerShare", 40, "seniorShare", 60, "connectsPerL1", 3.0),
                Map.of("callsPerDay", 14.0, "connectRate", 30, "connectsPerL1", 5.0, "bdrsCompared", 7),
                Map.of("overdueFollowUps", 0L, "neverCalled", 0L, "staleAccounts", 0L));
        f.put("sql", Map.of("submitted", 5L, "approved", 5L, "rejected", 0L));
        f.put("noShows", 0L);
        List<String> p = AssistantService.coachPoints(f);
        String all = String.join("\n", p);
        assertTrue(all.contains("L1 meetings done: 21 against a target of 20. Target reached."), all);
        assertEquals(2, p.stream().filter(x -> x.startsWith("Going well")).count(), all);
        assertEquals(0, p.stream().filter(x -> x.startsWith("To improve")).count(), all);
        assertEquals("Focus this week: keep the same rhythm.", p.get(p.size() - 1));
    }

    @Test
    void tooFewCallsAreNotComparedWithTheTeam() {
        List<String> p = AssistantService.coachPoints(facts(Map.of("l1Done", 5L, "l1DoneTarget", 20.0),
                Map.of("calls", 17L, "callsPerDay", 0.8, "connectRate", 94, "noAnswerShare", 6, "seniorShare", 65, "connectsPerL1", 1.2),
                Map.of("callsPerDay", 0.1, "connectRate", 100, "bdrsCompared", 1), Map.of("overdueFollowUps", 0L, "neverCalled", 0L, "staleAccounts", 0L)));
        String all = String.join("\n", p);
        assertTrue(all.contains("only 17 calls were logged"), all);
        assertFalse(all.contains("team average"), all);
    }

    @Test
    void noCallsSaysSoInsteadOfComparing() {
        List<String> p = AssistantService.coachPoints(facts(Map.of("l1Done", 0L, "l1DoneTarget", 20.0), Map.of("calls", 0L, "callsPerDay", 0.0),
                Map.of("bdrsCompared", 0), Map.of("overdueFollowUps", 0L, "neverCalled", 0L, "staleAccounts", 0L)));
        assertTrue(String.join("\n", p).contains("no calls were logged in this period"));
    }
}
