package com.jiaxian.analytics.export;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 导出 CSV：UTF-8 带 BOM（Excel 直接打开中文不乱码）、CRLF 换行；含逗号、引号、换行的单元格加引号并转义；
 * 以 = + - @ 开头的文本前面加一个单引号，避免在 Excel 里被当成公式执行（CSV 注入）。
 */
public final class CsvWriter {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private CsvWriter() {
    }

    public static byte[] write(List<String> header, List<List<?>> rows) {
        StringBuilder text = new StringBuilder();
        appendRow(text, header);
        for (List<?> row : rows) {
            appendRow(text, row);
        }
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, result, 0, BOM.length);
        System.arraycopy(body, 0, result, BOM.length, body.length);
        return result;
    }

    /** 一个"下载为 filename"的 CSV 响应；中文文件名用 RFC 5987 编码。 */
    public static ResponseEntity<byte[]> download(String filename, List<String> header, List<List<?>> rows) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(write(header, rows));
    }

    private static void appendRow(StringBuilder text, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(cell(cells.get(i)));
        }
        text.append("\r\n");
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 && !isNumber(text)) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /** 负数、带加号的数字不是公式，不加单引号（否则 -5 会变成文本）。 */
    private static boolean isNumber(String text) {
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
