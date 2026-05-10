package server;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import server.repository.AdminRepo;
import server.repository.UserRepo;

@Service
@RequiredArgsConstructor
//TODO: Expand auth to 2-factor and add sharing (hopefully without rewriting half the fucking code)
public class AuthService {

    private final UserRepo userRepository;
    private final AdminRepo adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JWTHandler jwtHandler;
    private final ReactiveAuthenticationManager authenticationManager;
    private final FolderController folderCtl;
    private final TotpService totpService;

    public Mono<String> register(String username, String password) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("USER"); // for GrantedAuthority (for now)
        return userRepository.save(user)
                .flatMap(saved -> folderCtl.createRootFolder(saved.getId()).log("New user ID: "+saved.getId().toString())
                        .thenReturn(saved))
                .flatMap(saved -> folderCtl.createBinFolder(saved.getId())
                        .thenReturn(saved))
                .map(saved -> jwtHandler.generateToken(saved));
    }

    public Mono<String> login(String username, String password) {
        return login(username, password, null);
    }

    /**
     * Unified login: accepts both regular users and admins. If the user has
     * 2FA enrolled (totp_enabled=true), a 6-digit {@code totpCode} from their
     * authenticator app is required and validated AFTER the password check.
     * Failure → 401 with reason "TOTP required" or "TOTP invalid".
     */
    public Mono<String> login(String username, String password, String totpCode) {
        return authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(username, password)
                )
                .map(auth -> (IdentifiedPrincipal) auth.getPrincipal())
                .flatMap(principal -> {
                    if (!(principal instanceof UserEntity user)
                            || !Boolean.TRUE.equals(user.getTotpEnabled())) {
                        return Mono.just(principal);
                    }
                    if (totpCode == null || totpCode.isBlank()) {
                        return Mono.error(new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.UNAUTHORIZED, "TOTP required"));
                    }
                    if (!totpService.verify(user.getTotpSecret(), totpCode)) {
                        return Mono.error(new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.UNAUTHORIZED, "TOTP invalid"));
                    }
                    return Mono.just(principal);
                })
                .map(jwtHandler::generateToken);
    }
    
    // ADMIN
    
    public Mono<String> registerAdmin(String username, String password) {
        AdminEntity user = new AdminEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("ADMIN"); // admin role
        return adminRepository.save(user)
                .map(saved -> jwtHandler.generateToken(saved));
    }

    /**
     * @deprecated kept for backward compatibility — {@link #login(String, String)}
     * now accepts both user and admin credentials. New callers should use the
     * unified endpoint and route based on the JWT's {@code role} claim.
     */
    @Deprecated
    public Mono<String> loginAdmin(String username, String password) {
        return login(username, password);
    }
}
