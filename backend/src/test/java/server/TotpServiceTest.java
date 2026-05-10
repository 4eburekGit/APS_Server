package server;

import org.junit.jupiter.api.Test;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;

import static org.junit.jupiter.api.Assertions.*;

class TotpServiceTest {

    private final TotpService svc = new TotpService();

    @Test
    void newSecretIsBase32WithEnoughEntropy() {
        String s = svc.newSecret();
        assertNotNull(s);
        assertTrue(s.length() >= 32, "secret should be at least 32 chars (base32 of 20 bytes)");
        // base32 alphabet: A-Z 2-7 + optional padding
        assertTrue(s.matches("[A-Z2-7=]+"));
    }

    @Test
    void otpAuthUriHasIssuerAndSecret() {
        String s = svc.newSecret();
        String uri = svc.otpAuthUri("alice", s);
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("alice"));
        assertTrue(uri.contains("secret=" + s));
        assertTrue(uri.contains("issuer=APS_Server"));
    }

    @Test
    void verifyAcceptsCorrectCodeAndRejectsBad() throws CodeGenerationException {
        String s = svc.newSecret();
        // Generate the current code with the same algorithm/digits the service expects.
        CodeGenerator gen = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        long bucket = System.currentTimeMillis() / 1000 / 30;
        String good = gen.generate(s, bucket);

        assertTrue(svc.verify(s, good), "current TOTP code should verify");
        assertFalse(svc.verify(s, "000000"));
        assertFalse(svc.verify(null, good));
        assertFalse(svc.verify(s, null));
    }
}
