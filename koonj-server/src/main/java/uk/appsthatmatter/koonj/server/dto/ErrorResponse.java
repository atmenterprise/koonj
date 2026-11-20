package uk.appsthatmatter.koonj.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "HTTP error with a status code, category and human-readable message")
public record ErrorResponse(int status, String error, String message) {}
