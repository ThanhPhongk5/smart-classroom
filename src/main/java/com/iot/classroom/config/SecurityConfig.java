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
                .requestMatchers("/api/device/**", "/login.html").permitAll()
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
        return name -> repo.findByUsername(name)
            .map(u -> User.withUsername(u.getUsername()).password(u.getPassword()).roles(u.getRole()).build())
            .orElseThrow(() -> new UsernameNotFoundException(name));
    }
}
