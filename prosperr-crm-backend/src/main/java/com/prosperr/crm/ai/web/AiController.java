package com.prosperr.crm.ai.web;

import com.prosperr.crm.ai.application.AssistantService;
import com.prosperr.crm.ai.application.CallNoteService;
import com.prosperr.crm.ai.application.CardScanService;
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
    private final AssistantService assistant;
    private final CardScanService cards;
    private final CurrentUser current;

    public AiController(CallNoteService callNotes, FormFillService forms, AssistantService assistant, CardScanService cards, CurrentUser current) {
        this.callNotes = callNotes; this.forms = forms; this.assistant = assistant; this.cards = cards; this.current = current;
    }

    /** Visiting card photo (JPG/PNG) to contact fields, with confidence and duplicate matches. Saves nothing. */
    @PostMapping(value = "/card", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> scanCard(@RequestPart("image") MultipartFile image) throws IOException {
        return cards.scan(current.get().userId(), image.getBytes(), image.getOriginalFilename(), image.getContentType());
    }

    /** The BDR's spoken morning brief (text + WAV audio as base64) in the chosen language, e.g. hi-IN. Read-only. */
    @GetMapping("/assistant/brief")
    public AssistantService.Reply brief(@RequestParam(defaultValue = "en-IN") String lang, @RequestParam(defaultValue = "false") boolean refresh) {
        return assistant.brief(current.get().userId(), lang, refresh);
    }

    /** Ask the assistant by voice (16 kHz WAV); it answers in the language spoken. Read-only. */
    @PostMapping(value = "/assistant/ask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AssistantService.Reply askAudio(@RequestPart("audio") MultipartFile audio, @RequestParam(required = false) String lang) throws IOException {
        return assistant.ask(current.get().userId(), audio.getBytes(), null, lang);
    }

    /** Ask the assistant by typing. */
    @PostMapping(value = "/assistant/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AssistantService.Reply askText(@RequestBody Map<String, String> body) {
        return assistant.ask(current.get().userId(), null, body.get("text"), body.get("lang"));
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
