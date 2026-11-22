package uk.appsthatmatter.koonj.server.service;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;

final class StorageErrors {
    private StorageErrors() {}

    static String describe(Exception failure) {
        if (failure instanceof S3Exception s3) return "S3 request failed with HTTP " + s3.statusCode();
        if (failure instanceof SdkException) return "Could not communicate with the S3 endpoint";
        if (failure instanceof org.springframework.dao.DataAccessException) return "Could not persist job or catalog data";
        if (failure instanceof AccessDeniedException denied) return "Access denied: " + denied.getFile();
        if (failure instanceof NoSuchFileException missing) return "File or path not found: " + missing.getFile();
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }
}
