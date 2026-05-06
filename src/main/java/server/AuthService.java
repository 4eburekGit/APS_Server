package server;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import server.repository.UserRepo;

@Service
@RequiredArgsConstructor
//TODO: Expand auth to 2-factor and add sharing (hopefully without rewriting half the fucking code)
public class AuthService {

    private final UserRepo userRepository;
    // private final AdminRepo adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JWTHandler jwtHandler;
    private final ReactiveAuthenticationManager authenticationManager;
    private final FolderController folderCtl;

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
        return authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(username, password)
                )
                .map(auth -> (UserEntity) auth.getPrincipal())
                .map(jwtHandler::generateToken);
    }
    
    // ADMIN -- DEPRECATED
    /*
    
    public Mono<String> registerAdmin(String username, String password) {
        AdminEntity user = new AdminEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("ADMIN"); // admin role
        return adminRepository.save(user)
                .map(saved -> jwtHandler.generateToken(saved));
    }

    public Mono<String> loginAdmin(String username, String password) {
        return authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(username, password)
                )
                .map(auth -> (AdminEntity) auth.getPrincipal())
                .map(jwtHandler::generateToken);
    }
    */
}
