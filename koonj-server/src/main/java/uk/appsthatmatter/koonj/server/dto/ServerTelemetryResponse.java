package uk.appsthatmatter.koonj.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Server health and JVM measurements sampled at request time")
public record ServerTelemetryResponse(String health, Instant sampledAt, long uptimeSeconds, String javaVersion,
                                      long heapUsedBytes, long heapCommittedBytes, int livePlatformThreads,
                                      int availableProcessors) {}
