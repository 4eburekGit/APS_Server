package server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrashCleanupJobTest {

    @Mock
    private DatabaseClient databaseClient;

    @TempDir
    Path tempDir;

    @InjectMocks
    private TrashCleanupJob job;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(job, "storagePath", tempDir.toString());
    }

    /**
     * Wires the four DB statements the cleanup runs (in order):
     *  1) SELECT metadata expired-files
     *  2) DELETE FROM metadata WHERE id = :id  (per file)
     *  3) SELECT folders expired-folders
     *  4) (per folder) SELECT subtree files + DELETE folder row
     *
     * For this test we only care that the file/folder paths get walked and
     * the rowsUpdated mono completes. We don't actually verify the row
     * count returned by Postgres.
     */
    @SuppressWarnings("unchecked")
    @Test
    void purgeExpiredDeletesBlobAndRow() throws Exception {
        // Set up a real blob on disk so deleteIfExists has something to do.
        Path blob = tempDir.resolve("a.bin");
        Files.writeString(blob, "x");
        assertTrue(Files.exists(blob));

        java.util.UUID fileId = java.util.UUID.randomUUID();

        DatabaseClient.GenericExecuteSpec spec =
                mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        when(databaseClient.sql(anyString())).thenReturn(spec);

        // Files SELECT returns one expired file, then DELETE returns 1 row.
        when(spec.map(any(Function.class))).thenAnswer(inv -> {
            // The cleanup uses 2 different .map(Function) calls:
            //   - one mapping a Readable to ExpiredFile
            //   - one mapping a Readable to ExpiredFolder
            // We try both — return whichever one the lambda was looking for.
            Function<io.r2dbc.spi.Readable, ?> fn = inv.getArgument(0);
            io.r2dbc.spi.Readable readable = mock(io.r2dbc.spi.Readable.class);
            lenient().when(readable.get("id", java.util.UUID.class)).thenReturn(fileId);
            lenient().when(readable.get("storage_path", String.class)).thenReturn(blob.toString());
            lenient().when(readable.get("owner_id", java.util.UUID.class)).thenReturn(java.util.UUID.randomUUID());
            lenient().when(readable.get("name", String.class)).thenReturn("trashed");

            Object mapped = fn.apply(readable);

            RowsFetchSpec<Object> rowsSpec = mock(RowsFetchSpec.class);
            // First call (files) returns one row; folders SELECT returns empty.
            // Distinguish by inspecting class of mapped:
            if (mapped.getClass().getSimpleName().equals("ExpiredFile")) {
                when(rowsSpec.all()).thenReturn(Flux.just(mapped));
            } else {
                when(rowsSpec.all()).thenReturn(Flux.empty());
            }
            return rowsSpec;
        });

        // DELETE statements return rowsUpdated = 1.
        FetchSpec fetchSpec = mock(FetchSpec.class);
        when(spec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.rowsUpdated()).thenReturn(Mono.just(1L));

        StepVerifier.create(job.purgeExpired(Instant.now()))
                .assertNext(stats -> {
                    // 1 file expired, 0 folders expired.
                    assertTrue(stats.filesPurged() >= 1);
                })
                .verifyComplete();

        // Blob got removed from disk.
        assertFalse(Files.exists(blob), "blob should be deleted");
    }

    @Test
    void runDailyDoesNotCrashOnEmpty() {
        DatabaseClient.GenericExecuteSpec spec =
                mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        when(databaseClient.sql(anyString())).thenReturn(spec);

        @SuppressWarnings("unchecked")
        RowsFetchSpec<Object> rowsSpec = mock(RowsFetchSpec.class);
        when(spec.map(any(Function.class))).thenReturn(rowsSpec);
        when(rowsSpec.all()).thenReturn(Flux.empty());

        // Just check no NPE / exception escapes.
        job.runDaily();
    }
}
