package uk.appsthatmatter.koonj.server.entity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record TransferPlan(String storageClass, List<String> sourcePaths, List<String> targetFolders,
                           Map<String, String> restorePaths, Set<String> exactPaths, UUID interactiveFileId) {}
