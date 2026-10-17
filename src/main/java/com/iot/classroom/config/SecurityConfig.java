package com.iot.classroom.config;

import com.iot.classroom.repository.Repos;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/device/**", "/login.html", "/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/rooms").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/rooms/*/layout").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/rooms/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/schedules/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), new AntPathRequestMatcher("/api/**")))
            .formLogin(f -> f.loginPage("/login.html").loginProcessingUrl("/login")
                .defaultSuccessUrl("/", true).failureUrl("/login.html?error").permitAll())
            .logout(l -> l.logoutSuccessUrl("/login.html"));
        return http.build();
    }

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService userDetailsService(Repos.UserRepo repo) {
        return name -> {
            String uName = name != null ? name.trim().toLowerCase() : "";
            return repo.findByUsername(uName)
                .or(() -> repo.findByUsername(name))
                .map(u -> {
                    String cleanRole = u.getRole() != null ? u.getRole().replace("ROLE_", "") : "TEACHER";
                    return User.withUsername(u.getUsername())
                        .password(u.getPassword())
                        .roles(cleanRole)
                        .build();
                })
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy người dùng: " + name));
        };
    }
}
