package com.prosperr.crm.notification.web;

import com.prosperr.crm.notification.application.NotificationService;
import com.prosperr.crm.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notifications;
    private final CurrentUser current;

    public NotificationController(NotificationService notifications, CurrentUser current) {
        this.notifications = notifications; this.current = current;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean unread) {
        return notifications.list(current.get().userId(), unread);
    }

    @PostMapping("/{id}/read")
    public void read(@PathVariable UUID id) { notifications.markRead(current.get().userId(), id); }

    @PostMapping("/read-all")
    public void readAll() { notifications.markAllRead(current.get().userId()); }
}
