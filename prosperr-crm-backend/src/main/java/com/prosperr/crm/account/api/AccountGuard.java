package com.prosperr.crm.account.api;

import com.prosperr.crm.shared.security.AccountAccess;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.Actor;

import java.util.UUID;

/** Record-level permission checks (PRD permission table), including admin overrides. */
public interface AccountGuard {
    AccountAccess access(UUID accountId);
    boolean can(Actor actor, Action action, UUID accountId);
    /** Throws ForbiddenException when not allowed. */
    void require(Actor actor, Action action, UUID accountId);
}
