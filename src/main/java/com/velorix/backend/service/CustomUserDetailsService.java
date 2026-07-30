package com.velorix.backend.service;

import com.velorix.backend.model.User;
import com.velorix.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        String rawRole = user.getRole() != null ? user.getRole() : "USER";
        String normalizedRole = rawRole.toUpperCase().startsWith("ROLE_") 
                ? rawRole.toUpperCase() 
                : "ROLE_" + rawRole.toUpperCase();

        return new com.velorix.backend.security.CustomUserDetails(
                user,
                Collections.singletonList(new SimpleGrantedAuthority(normalizedRole))
        );
    }
}