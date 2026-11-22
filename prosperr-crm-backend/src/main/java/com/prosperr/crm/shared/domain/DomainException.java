package com.prosperr.crm.shared.domain;

/** A business rule was broken. Mapped to HTTP 422 by the web layer. */
public class DomainException extends RuntimeException {
    private final String code;
    public DomainException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
