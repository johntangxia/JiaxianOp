package com.jiaxian.analytics.export;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void startsWithAUtf8BomAndUsesCrlfLineEndings() {
        byte[] bytes = CsvWriter.write(List.of("姓名", "部门"), List.of(List.of("员工-1", "战略")));

        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        assertThat(text(bytes)).isEqualTo("﻿姓名,部门\r\n员工-1,战略\r\n");
    }

    @Test
    void quotesCellsWithCommasQuotesAndNewlinesAndEscapesQuotes() {
        assertThat(CsvWriter.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvWriter.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvWriter.cell("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(CsvWriter.cell("plain")).isEqualTo("plain");
    }

    @Test
    void nullsAreEmptyAndOtherTypesUseTheirTextForm() {
        assertThat(CsvWriter.cell(null)).isEmpty();
        assertThat(CsvWriter.cell(42L)).isEqualTo("42");
        assertThat(CsvWriter.cell(LocalDate.of(2026, 9, 4))).isEqualTo("2026-09-04");
        assertThat(text(CsvWriter.write(List.of("a", "b", "c"), List.of(Arrays.asList("x", null, 3))))).endsWith("x,,3\r\n");
    }

    @Test
    void textThatLooksLikeAFormulaIsNeutralisedButNumbersAreLeftAlone() {
        assertThat(CsvWriter.cell("=SUM(A1:A9)")).isEqualTo("'=SUM(A1:A9)");
        assertThat(CsvWriter.cell("+cmd")).isEqualTo("'+cmd");
        assertThat(CsvWriter.cell("-cmd")).isEqualTo("'-cmd");
        assertThat(CsvWriter.cell("@SUM(1)")).isEqualTo("'@SUM(1)");
        assertThat(CsvWriter.cell("=1,2")).isEqualTo("\"'=1,2\"");   // 先中和再加引号
        assertThat(CsvWriter.cell(-5)).isEqualTo("-5");
        assertThat(CsvWriter.cell("-3.5")).isEqualTo("-3.5");
        assertThat(CsvWriter.cell("a=b")).isEqualTo("a=b");
    }

    @Test
    void downloadSetsCsvContentTypeAndAnEncodedAttachmentFilename() {
        ResponseEntity<byte[]> response = CsvWriter.download("用户列表_2026-09-04.csv", List.of("a"), List.of(List.of("1")));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/csv;charset=UTF-8");
        String disposition = response.getHeaders().getFirst("Content-Disposition");
        assertThat(disposition).startsWith("attachment").contains("filename*=UTF-8''");
        assertThat(disposition).doesNotContain("用户");     // 中文被编码成 %XX
        assertThat(text(response.getBody())).contains("a\r\n1\r\n");
    }
}
