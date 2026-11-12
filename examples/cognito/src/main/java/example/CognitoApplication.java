package example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SpringBootApplication
@RestController
public class CognitoApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(CognitoApplication.class, args);
        if (Boolean.getBoolean("example.smoke")) context.close();
    }

    @GetMapping("/me")
    Map<String, Object> me(@AuthenticationPrincipal Jwt token) {
        return Map.of("username", token.getClaimAsString("username"), "groups", token.getClaimAsStringList("cognito:groups"));
    }

    @GetMapping("/admin")
    Map<String, String> admin() { return Map.of("message", "Administrator access granted"); }
}
