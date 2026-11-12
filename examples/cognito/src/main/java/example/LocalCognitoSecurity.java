package example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.file.Path;

/** Local-only token policy for AWS Emulator 1.0.322, which signs with ES256. */
@Configuration
@Profile("local")
class LocalCognitoSecurity {
    record CognitoConfiguration(String userPoolId, String clientId, String region, String endpoint, String issuer, String jwksUri) {
        static CognitoConfiguration read() throws Exception {
            return new ObjectMapper().readValue(Path.of(System.getProperty("example.cognito.configuration")).toFile(), CognitoConfiguration.class);
        }
    }

    @Bean JwtDecoder jwtDecoder() throws Exception {
        var config = CognitoConfiguration.read();
        var decoder = NimbusJwtDecoder.withJwkSetUri(config.jwksUri()).jwsAlgorithm(SignatureAlgorithm.ES256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(config.issuer()),
            new JwtClaimValidator<String>("token_use", "access"::equals),
            new JwtClaimValidator<String>("client_id", config.clientId()::equals)));
        return decoder;
    }

    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        var groups = new JwtGrantedAuthoritiesConverter();
        groups.setAuthoritiesClaimName("cognito:groups");
        groups.setAuthorityPrefix("ROLE_");
        var authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(groups);
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(requests -> requests.requestMatchers("/admin").hasRole("admins").anyRequest().authenticated())
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(authentication)))
            .build();
    }
}
