package server;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.stereotype.Service;

/**
 * Tiny wrapper around RFC-6238 TOTP for FR#17. Owns no state — enrolled
 * secret lives on the {@code users} row (totp_secret/totp_enabled columns).
 */
@Service
public class TotpService {

    private static final String ISSUER = "APS_Server";

    private final SecretGenerator secretGen = new DefaultSecretGenerator(); // 32-byte base32
    private final CodeVerifier verifier;

    public TotpService() {
        TimeProvider time = new SystemTimeProvider();
        CodeGenerator gen = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        DefaultCodeVerifier v = new DefaultCodeVerifier(gen, time);
        v.setAllowedTimePeriodDiscrepancy(1); // allow ±30s clock drift
        this.verifier = v;
    }

    /** Generate a fresh base32 secret. */
    public String newSecret() {
        return secretGen.generate();
    }

    /** Build the otpauth:// URI a user pastes/scans into Google Authenticator. */
    public String otpAuthUri(String username, String secret) {
        return new QrData.Builder()
                .label(ISSUER + ":" + username)
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build()
                .getUri();
    }

    /** Verify a 6-digit code against a stored secret. */
    public boolean verify(String secret, String code) {
        if (secret == null || code == null) return false;
        return verifier.isValidCode(secret, code);
    }
}
