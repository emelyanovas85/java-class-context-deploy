package service.structure.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * @param pinnedRefs     зафиксированные SHA (при создании сессии)
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
        @JsonIgnore List<Diff> diffs,
        PinnedRefs pinnedRefs
) {
    /** Конструктор без pin SHA (legacy / тесты). */
    public MergeRequestInfo(
            Long iid,
            String title,
            String state,
            String sourceBranch,
            String targetBranch,
            String authorUsername,
            List<CommitInfo> commits,
            List<String> changedFiles,
            List<Diff> diffs) {
        this(iid, title, state, sourceBranch, targetBranch, authorUsername,
                commits, changedFiles, diffs, null);
    }
}
