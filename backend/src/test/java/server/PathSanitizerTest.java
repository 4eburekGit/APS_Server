package server;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hardening tests for {@link PathSanitizer}.
 *
 * <p>The class is purely an input-validation gate — there are no I/O paths to
 * stub. We verify each rejection branch (null, blank, '.', '..', slash,
 * backslash, NUL, control char, > 255 chars) and the happy path (returns
 * the trimmed name unchanged). Together this exercises every line of
 * {@code sanitizeName} plus the two convenience wrappers.
 */
class PathSanitizerTest {

    private static void assertBadRequest(Runnable r) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, r::run);
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void sanitizeFilename_validName_returnsTrimmedInput() {
        assertEquals("report.pdf", PathSanitizer.sanitizeFilename("  report.pdf  "));
    }

    @Test
    void sanitizeFilename_unicodeName_isAllowed() {
        // Non-ASCII letters are not control chars and not separators — fine.
        assertEquals("отчёт.pdf", PathSanitizer.sanitizeFilename("отчёт.pdf"));
    }

    @Test
    void sanitizeFilename_null_rejected() {
        assertBadRequest(() -> PathSanitizer.sanitizeFilename(null));
    }

    @Test
    void sanitizeFilename_blank_rejected() {
        assertBadRequest(() -> PathSanitizer.sanitizeFilename("   "));
    }

    @Test
    void sanitizeFilename_dot_rejected() {
        assertBadRequest(() -> PathSanitizer.sanitizeFilename("."));
    }

    @Test
    void sanitizeFilename_dotDot_rejected() {
        // Classic traversal attempt.
        assertBadRequest(() -> PathSanitizer.sanitizeFilename(".."));
    }

    @Test
    void sanitizeFilename_forwardSlash_rejected() {
        assertBadRequest(() -> PathSanitizer.sanitizeFilename("../etc/passwd"));
    }

    @Test
    void sanitizeFilename_backslash_rejected() {
        // Windows-style traversal must also be blocked.
        assertBadRequest(() -> PathSanitizer.sanitizeFilename("..\\windows\\system32"));
    }

    @Test
    void sanitizeFilename_nul_rejected() {
        // NUL truncates filenames in C-stdlib calls (poison-byte attacks).
        assertBadRequest(() -> PathSanitizer.sanitizeFilename("evil\u0000.txt"));
    }

    @Test
    void sanitizeFilename_controlChar_rejected() {
        // Tabs/newlines etc. would break logging and produce surprising paths.
        assertBadRequest(() -> PathSanitizer.sanitizeFilename("bad\tname.txt"));
    }

    @Test
    void sanitizeFilename_tooLong_rejected() {
        String tooLong = "a".repeat(256);
        assertBadRequest(() -> PathSanitizer.sanitizeFilename(tooLong));
    }

    @Test
    void sanitizeFilename_atMaxLength_accepted() {
        String maxOk = "a".repeat(255);
        assertEquals(maxOk, PathSanitizer.sanitizeFilename(maxOk));
    }

    @Test
    void sanitizeFolderName_validName_returnsTrimmedInput() {
        assertEquals("Documents", PathSanitizer.sanitizeFolderName("Documents"));
    }

    @Test
    void sanitizeFolderName_traversal_rejected() {
        // Same rule set, different label — verify the wrapper actually delegates.
        assertBadRequest(() -> PathSanitizer.sanitizeFolderName("../"));
    }

    @Test
    void sanitizeName_errorMessageIncludesLabel() {
        // The label is user-facing in 400 responses; make sure it surfaces.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> PathSanitizer.sanitizeName(null, "Custom"));
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("Custom"),
                "expected reason to contain label, got: " + ex.getReason());
    }
}
