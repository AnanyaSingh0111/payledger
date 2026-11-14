package in.payledger.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI payLedgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayLedger API")
                        .version("1.0")
                        .description(
                                "Secure wallet and transaction API with JWT authentication, "
                                        + "pessimistic locking, and idempotent transfers."
                        )
                )
                .servers(java.util.List.of(
                        new io.swagger.v3.oas.models.servers.Server().url("/").description("Current server")))
                .addSecurityItem(
                        new SecurityRequirement()
                                .addList("bearerAuth")
                )
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                )
                );
    }
}