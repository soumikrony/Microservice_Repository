package com.example.auth;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUserDetailsService.class);

    private final AuthUserRepository authUserRepository;

    public DatabaseUserDetailsService(AuthUserRepository authUserRepository) {
        this.authUserRepository = authUserRepository;
    }

    @Override
    @Cacheable(cacheNames = "auth-users", key = "#username")
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AuthUser dbUser = authUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        String[] roles = Arrays.stream(dbUser.getRoles().split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(r -> r.replace("ROLE_", ""))
                .toArray(String[]::new);

        if (roles.length == 0) {
            roles = new String[] { "USER" };
        }

        log.info("Loaded user={} from PostgreSQL (roles={})", username, String.join(",", roles));

        return User.withUsername(dbUser.getUsername())
                .password(dbUser.getPassword())
                .disabled(!dbUser.isEnabled())
                .roles(roles)
                .build();
    }
}
