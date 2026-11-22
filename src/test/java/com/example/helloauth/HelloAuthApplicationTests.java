package com.example.helloauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class HelloAuthApplicationTests {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder jwtEncoder;

    private String login(String username, String password) throws Exception {
        String body = json.writeValueAsString(new java.util.HashMap<>(java.util.Map.of(
            "username", username, "password", password)));
        String response = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andExpect(jsonPath("$.expires_in").value(3600))
            .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        return node.get("access_token").asText();
    }

    @Test
    void helloWithoutTokenIsRejected() throws Exception {
        mvc.perform(get("/api/hello"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    void loginWithTestAccountThenHelloReturnsHelloWorld() throws Exception {
        String token = login("test", "123456");
        assertThat(token.split("\\.")).hasSize(3); // header.payload.signature

        mvc.perform(get("/api/hello").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(content().string("Hello World"));
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSame401() throws Exception {
        for (String[] creds : new String[][] {{"test", "wrong"}, {"nobody", "123456"}}) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + creds[0] + "\",\"password\":\"" + creds[1] + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
        }
    }

    @Test
    void blankOrMissingFieldsAreBadRequest() throws Exception {
        for (String body : new String[] {"{\"username\":\"test\"}", "{\"username\":\"\",\"password\":\"123456\"}", "{}"}) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = login("test", "123456");
        String[] parts = token.split("\\.");
        // Swap in a payload claiming a different user, keep the original signature.
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"sub\":\"admin\",\"iss\":\"hello-auth\",\"exp\":9999999999}".getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        mvc.perform(get("/api/hello").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        String expired = encode(JwtClaimsSet.builder().issuer("hello-auth").subject("test")
            .issuedAt(past.minusSeconds(3600)).expiresAt(past).build());

        mvc.perform(get("/api/hello").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        String other = encode(JwtClaimsSet.builder().issuer("someone-else").subject("test")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build());

        mvc.perform(get("/api/hello").header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void loginEndpointOnlyAcceptsPost() throws Exception {
        // GET is not opened up by the security rules, so it is treated as an unauthenticated request.
        mvc.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());
    }

    private String encode(JwtClaimsSet claims) {
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    }
}
