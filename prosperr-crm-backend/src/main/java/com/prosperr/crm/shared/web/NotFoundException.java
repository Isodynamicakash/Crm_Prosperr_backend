package com.prosperr.crm.shared.web;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String what, Object id) { super(what + " not found: " + id); }
}
