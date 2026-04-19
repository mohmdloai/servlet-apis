package com.loai.inventory.domain.model;

import java.time.Instant;

public record Environment(Instant requestTime, String sourceIp, String userAgent) {}
