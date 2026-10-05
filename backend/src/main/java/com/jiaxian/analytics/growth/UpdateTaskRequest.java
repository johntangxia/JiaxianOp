package com.jiaxian.analytics.growth;

import java.time.LocalDate;

/** 修改任务：只改传了的字段；对象、指标、基线不可改。 */
public record UpdateTaskRequest(String title, String owner, TaskStatus status, Double targetValue, LocalDate dueDate,
                                String note) {
}
