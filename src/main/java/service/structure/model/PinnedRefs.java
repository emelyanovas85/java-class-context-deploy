package service.structure.model;

/**
 * Зафиксированные SHA коммитов MR на момент создания сессии.
 *
 * @param sourceSha head_sha — снимок source-ветки MR
 * @param targetSha start_sha — снимок target-ветки MR
 * @param baseSha   merge base
 */
public record PinnedRefs(String sourceSha, String targetSha, String baseSha) {}
