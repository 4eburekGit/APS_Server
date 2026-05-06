package server;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hardens user-supplied path components (filenames, folder names) against
 * traversal attacks before we hand them to {@link java.nio.file.Path#resolve}.
 *
 * <p>The on-disk layout is rooted at {@code <storage.path>/<userId>/...}.
 * Every user-supplied segment must stay inside that root — a name like
 * {@code ../../etc/passwd} or {@code subdir/escape} would silently escape
 * the user's directory if we resolved it verbatim. This class rejects such
 * input at the controller boundary so storage-layer code can treat names
 * as trusted segments.
 */
public final class PathSanitizer {
    private PathSanitizer() {}

    private static final int MAX_LEN = 255;

    /**
     * Validates a single path segment supplied by a user.
     *
     * <p>Rejects:
     * <ul>
     *   <li>null / blank</li>
     *   <li>longer than 255 chars (filesystem limit on most OSes)</li>
     *   <li>contains {@code /}, {@code \}, or NUL ({@code \0})</li>
     *   <li>equals {@code .} or {@code ..}</li>
     *   <li>contains control characters (below {@code 0x20})</li>
     * </ul>
     *
     * @return the trimmed name (caller may keep using it)
     * @throws ResponseStatusException 400 BAD_REQUEST when invalid
     */
    public static String sanitizeName(String raw, String label) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required");
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " cannot be empty");
        }
        if (name.length() > MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is too long (max " + MAX_LEN + " chars)");
        }
        if (name.equals(".") || name.equals("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " cannot be '.' or '..'");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " cannot contain slashes");
        }
        if (name.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " cannot contain NUL");
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " cannot contain control characters");
            }
        }
        return name;
    }

    /** Convenience: sanitize a filename. */
    public static String sanitizeFilename(String raw) {
        return sanitizeName(raw, "Filename");
    }

    /** Convenience: sanitize a folder name. */
    public static String sanitizeFolderName(String raw) {
        return sanitizeName(raw, "Folder name");
    }
}
