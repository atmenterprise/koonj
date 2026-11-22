package uk.appsthatmatter.koonj.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.parameters.P;
import uk.appsthatmatter.koonj.server.security.KoonjRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.appsthatmatter.koonj.server.dto.*;
import uk.appsthatmatter.koonj.server.entity.TransferJob;
import uk.appsthatmatter.koonj.server.service.FileTransferService;
import uk.appsthatmatter.koonj.server.service.JobService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {
    private final FileTransferService transfers;
    private final JobService jobs;

    public TransferController(FileTransferService transfers, JobService jobs) {
        this.transfers = transfers;
        this.jobs = jobs;
    }

    @PostMapping("/archive")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Queue an archive", description = "Verify an S3 archive and keep the original file in COPY mode or replace it with a symbolic link in PURGE mode.")
    @PreAuthorize(KoonjRoles.ARCHIVE)
    public JobResponse archive(@P("request") @Valid @RequestBody TransferRequest request) {
        return transfers.submit(TransferJob.Operation.ARCHIVE, request);
    }

    @PostMapping("/recall")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Queue a recall", description = "Restore verified objects from an S3 bucket to mounted storage and optionally remove the S3 copies.")
    @PreAuthorize(KoonjRoles.OPERATE)
    public JobResponse recall(@Valid @RequestBody TransferRequest request) {
        return transfers.submit(TransferJob.Operation.RECALL, request);
    }

    public record SyncRecallRequest(@NotNull UUID fileId, @Min(1) @Max(60) Integer timeoutSeconds) {}

    @PostMapping("/recall/sync")
    @PreAuthorize(KoonjRoles.OPERATE)
    public ResponseEntity<FileTransferService.RecallResult> recallSync(@Valid @RequestBody SyncRecallRequest request) {
        var result = transfers.recallSync(request.fileId(), request.timeoutSeconds() == null ? 60 : request.timeoutSeconds());
        return ResponseEntity.status(result.estimatedReadyAt() == null ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result);
    }

    @GetMapping("/jobs")
    @Operation(summary = "List jobs", description = "Browse archive, recall and scan jobs in reverse creation order.")
    @PreAuthorize(KoonjRoles.OPERATE)
    public PageResponse<JobResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return jobs.list(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Read job progress", description = "Return status, file and byte counters, completion time, and any recorded errors.")
    @PreAuthorize(KoonjRoles.OPERATE)
    public JobResponse get(@PathVariable UUID jobId) { return jobs.get(jobId); }

    @GetMapping("/jobs/{jobId}/events")
    @PreAuthorize(KoonjRoles.OPERATE)
    public PageResponse<JobEventResponse> events(@PathVariable UUID jobId,
            @RequestParam(defaultValue = "false") boolean errorsOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        return jobs.events(jobId, errorsOnly, PageRequest.of(page, size));
    }

    @GetMapping("/jobs/{jobId}/files")
    @PreAuthorize(KoonjRoles.OPERATE)
    public PageResponse<FileMovementResponse> fileResults(@PathVariable UUID jobId,
            @RequestParam(defaultValue = "false") boolean failedOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        return jobs.fileResults(jobId, failedOnly, PageRequest.of(page, size));
    }
}
