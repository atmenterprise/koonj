package uk.appsthatmatter.koonj.server.dto;

import java.util.List;

public record ScanSourcesResponse(List<JobResponse> jobs, int existingScanCount) {}
