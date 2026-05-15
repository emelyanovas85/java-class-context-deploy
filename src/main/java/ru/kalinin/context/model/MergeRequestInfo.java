package ru.kalinin.context.model;

import org.gitlab4j.api.models.Diff;

import java.util.List;

/**
 * Метаданные мёрж-реквеста.
 *
 * @param iid            внутренний ID MR в проекте
 * @param title          заголовок MR
 * @param state          состояние: opened, merged, closed
 * @param sourceBranch   ветка с изменениями
 * @param targetBranch   целевая ветка
 * @param authorUsername логин автора
 * @param commits        список коммитов
 * @param changedFiles   пути к не-удалённым .java-файлам MR (для уровня 0)
 * @param diffs          полный список diff-записей MR (для построения индекса)
 */
public record MergeRequestInfo(
        Long iid,
        String title,
        String state,
        String sourceBranch,
        String targetBranch,
        String authorUsername,
        List<CommitInfo> commits,
        List<String> changedFiles,
        List<Diff> diffs
) {}
