package com.prosperr.crm.ai.web;

import com.prosperr.crm.ai.application.CallNoteService;
import com.prosperr.crm.ai.application.FormFillService;
import com.prosperr.crm.shared.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final CallNoteService callNotes;
    private final FormFillService forms;
    private final CurrentUser current;

    public AiController(CallNoteService callNotes, FormFillService forms, CurrentUser current) {
        this.callNotes = callNotes; this.forms = forms; this.current = current;
    }

    /** Lets the UI hide AI buttons when the server has no key. */
    @GetMapping("/status")
    public Map<String, Boolean> status() { return Map.of("voiceNotes", callNotes.enabled()); }

    /** Voice note (16 kHz WAV) to suggested call-log fields. Nothing is saved here. */
    @PostMapping(value = "/call-note", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CallNoteService.Suggestion fromAudio(@RequestPart("audio") MultipartFile audio) throws IOException {
        return callNotes.fromAudio(current.get().userId(), audio.getBytes());
    }

    /** Typed note to suggested call-log fields. */
    @PostMapping(value = "/call-note", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CallNoteService.Suggestion fromText(@RequestBody Map<String, String> body) {
        return callNotes.fromText(current.get().userId(), body.get("text"));
    }

    /** Voice note to suggested values for the MEETING, STAKEHOLDER or SQL form. Nothing is saved here. */
    @PostMapping(value = "/fill/{form}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> fillAudio(@PathVariable FormFillService.Form form, @RequestPart("audio") MultipartFile audio) throws IOException {
        return forms.fromAudio(current.get().userId(), form, audio.getBytes());
    }

    /** Typed or pasted text (an e-mail signature, meeting notes...) to suggested form values. */
    @PostMapping(value = "/fill/{form}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> fillText(@PathVariable FormFillService.Form form, @RequestBody Map<String, String> body) {
        return forms.fromText(current.get().userId(), form, body.get("text"));
    }
}
