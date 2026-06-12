package com.nexaplatform.dropshipping.infrastructure.security.user;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DropshippingUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserEntity u = userRepository.findByEmail(username.toLowerCase().trim())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        boolean locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(Instant.now());

        return User.withUsername(u.getId().toString()).password(u.getPasswordHash() == null ? "" : u.getPasswordHash())
                .authorities(Set.of(new SimpleGrantedAuthority(u.getRole().authority()))).accountExpired(false)
                .accountLocked(locked).credentialsExpired(false).disabled(!u.isActive()).build();
    }
}
