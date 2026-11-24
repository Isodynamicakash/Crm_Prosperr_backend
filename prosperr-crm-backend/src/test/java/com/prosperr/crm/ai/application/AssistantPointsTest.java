package com.prosperr.crm.ai.application;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The brief is built from data only: empty sections never appear, and the call list keeps its order. */
class AssistantPointsTest {

    static Map<String, Object> facts(int overdue, int leads, Long target, int done, int days) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("bdr", "Paramita");
        f.put("overdueCount", overdue);
        f.put("overdue", overdue == 0 ? List.of() : List.of(Map.of("title", "Call back", "company", "Zepto", "person", "Anmol Jaju", "due", "20 Nov 11:00")));
        f.put("dueTodayCount", 0); f.put("dueToday", List.of());
        f.put("newLeadsCount", leads);
        f.put("topNewLeads", leads == 0 ? List.of() : List.of(Map.of("person", "Kapil Hathi", "designation", "CFO", "company", "Amazon")));
        f.put("sequenceReplies", List.of()); f.put("runningSequences", 0); f.put("revivalsDue", 0);
        f.put("meetingsTodayAndTomorrow", List.of());
        Map<String, Object> t = new HashMap<>();
        t.put("month", "NOVEMBER"); t.put("l1DoneTarget", target); t.put("l1Done", done); t.put("workingDaysLeft", days);
        f.put("target", t);
        f.put("callsToday", 0);
        return f;
    }

    @Test
    void emptySectionsAreLeftOut() {
        String all = String.join(" ", AssistantService.points(facts(0, 230, 20L, 9, 5)));
        assertFalse(all.contains("overdue"), all);
        assertFalse(all.contains("replied"), all);
        assertTrue(all.contains("230 new leads"), all);
        assertTrue(all.contains("11 more L1 meetings needed with 5 working days left, so about 3 L1 meetings a day"), all);
    }

    @Test
    void callListPutsOverdueBeforeNewLeads() {
        List<String> list = AssistantService.callList(facts(1, 5, null, 0, 5));
        assertTrue(list.get(0).startsWith("1. Anmol Jaju") && list.get(0).contains("overdue"), list.toString());
        assertTrue(list.get(1).startsWith("2. Kapil Hathi"), list.toString());
    }

    @Test
    void targetReachedAndNothingWaiting() {
        assertTrue(String.join(" ", AssistantService.points(facts(0, 0, 20L, 21, 3))).contains("is reached"));
        assertEquals(List.of("Nothing is waiting: no follow-ups and no uncalled leads."), AssistantService.callList(facts(0, 0, null, 0, 3)));
    }

    @Test
    void markdownIsStrippedForTheVoice() {
        assertEquals("Call Kapil first", AssistantService.clean("<think>x</think>**Call** _Kapil_ first"));
    }
}
