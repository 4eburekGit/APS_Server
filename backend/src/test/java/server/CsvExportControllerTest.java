package server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CsvExportControllerTest {

    @Test
    void quoteHandlesAllSpecialChars() {
        assertEquals("plain", CsvExportController.quote("plain"));
        assertEquals("", CsvExportController.quote(null));
        assertEquals("\"a, b\"", CsvExportController.quote("a, b"));
        assertEquals("\"with\nnewline\"", CsvExportController.quote("with\nnewline"));
        assertEquals("\"he said \"\"hi\"\"\"", CsvExportController.quote("he said \"hi\""));
    }

    @Test
    void toCsvBytesEmitsHeaderAndRowsWithRelativePath() {
        // Bytes-builder is the pure-logic core; the recursive-CTE Mono path is
        // covered by integration smoke (no good unit shape for raw R2DBC).
        java.util.List<CsvExportController.CsvRow> rows = java.util.List.of(
                new CsvExportController.CsvRow(
                        "Pictures/holiday", "img, 01.jpg", 12345L, "image/jpeg",
                        Instant.parse("2026-05-08T12:00:00Z"), null),
                new CsvExportController.CsvRow(
                        "", "plan.txt", 123L, "text/plain",
                        Instant.parse("2026-05-08T12:00:00Z"),
                        Instant.parse("2026-05-09T08:00:00Z"))
        );

        byte[] body = CsvExportController.toCsvBytes(rows);
        String csv = new String(body, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("path,filename,size,content_type,uploaded_at,updated_at\r\n"));
        // Comma in filename → quoted.
        // Path has no commas/quotes — emitted unquoted. Filename has a
        // comma → wrapped in quotes per RFC-4180.
        assertTrue(csv.contains("Pictures/holiday,\"img, 01.jpg\",12345,image/jpeg"));
        // Empty-path file at root level — empty first column followed by filename.
        assertTrue(csv.contains(",plan.txt,123,text/plain,2026-05-08T12:00:00Z,2026-05-09T08:00:00Z"));
    }
}
