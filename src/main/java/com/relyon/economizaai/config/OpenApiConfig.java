package com.relyon.economizaai.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the JWT bearer scheme so Swagger UI shows the Authorize button —
 * paste the token from POST /auth/login once and every locked request sends
 * the Authorization header. Applied globally; the actual enforcement lives in
 * SecurityConfig (public endpoints simply ignore the header).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI economizaiOpenApi() {
        var jwtBearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("JWT from POST /api/v1/auth/login (or /auth/register). Paste the raw token — no 'Bearer ' prefix needed.");
        return new OpenAPI()
                .info(new Info().title("economizai API").version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, jwtBearer))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
