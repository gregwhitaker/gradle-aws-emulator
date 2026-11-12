package example;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;

import static org.junit.jupiter.api.Assertions.*;

class CognitoIntegrationTest {
    @Test
    void signsInAndValidatesSignedTokensOverTrustedHttps() {
        try (var client = CognitoIdentityProviderClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String poolId = client.createUserPool(r -> r.poolName("cognito-spike")).userPool().id();
            String clientId = client.createUserPoolClient(r -> r.userPoolId(poolId).clientName("test")
                .generateSecret(false).explicitAuthFlowsWithStrings("ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"))
                .userPoolClient().clientId();
            client.adminCreateUser(r -> r.userPoolId(poolId).username("alice").messageAction("SUPPRESS"));
            client.adminSetUserPassword(r -> r.userPoolId(poolId).username("alice").password("Local-Test123!").permanent(true));
            var auth = client.initiateAuth(r -> r.clientId(clientId).authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(java.util.Map.of("USERNAME", "alice", "PASSWORD", "Local-Test123!"))).authenticationResult();
            String issuer = System.getenv("AWS_ENDPOINT_URL") + "/" + poolId;
            var decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json")
                .jwsAlgorithm(SignatureAlgorithm.ES256).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
            var token = decoder.decode(auth.idToken());
            assertEquals(java.util.List.of(clientId), token.getAudience());
            assertEquals("alice", token.getClaimAsString("cognito:username"));
            assertEquals("access", decoder.decode(auth.accessToken()).getClaimAsString("token_use"));
            assertThrows(Exception.class, () -> decoder.decode(auth.idToken().substring(0, auth.idToken().length() - 10) + "AAAAAAAAAA"));
        }
    }
}
