package com.loai.inventory.service.email;

/** A ready-to-send transactional email: one recipient, a subject, and an HTML body. */
public record EmailMessage(String to, String subject, String html) {}
