package uk.appsthatmatter.koonj.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Result of a storage connectivity and access probe")
public record ConnectionHealth(UUID connectionId, boolean healthy, String message) {}
