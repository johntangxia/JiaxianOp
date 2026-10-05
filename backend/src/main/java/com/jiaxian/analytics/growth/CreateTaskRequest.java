package com.jiaxian.analytics.growth;

import java.time.LocalDate;

/** 创建任务。title、entityType、metric、targetValue 必填；其余见设计文档 6.4。 */
public record CreateTaskRequest(String title, TaskType taskType, GrowthEntityType entityType, Long entityId,
                                String entityName, String metric, Double targetValue, String owner, LocalDate startDate,
                                LocalDate dueDate, String note, Long alertId) {
}
