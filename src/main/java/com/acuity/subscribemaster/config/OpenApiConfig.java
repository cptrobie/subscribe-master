package com.acuity.subscribemaster.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

  @Bean
  OpenAPI subscribeMasterOpenApi() {

    final String securitySchemeName = "bearerAuth";

    return new OpenAPI()
        .info(
            new Info()
                .title("Subscribe Master API")
                .description("Subscription management, payments, and billing API")
                .version("0.0.1-SNAPSHOT")
                .contact(new Contact().name("API Support Team").email("support@example.com"))
                .license(new License().name("Apache 2.0").url("https://apache.org")))
        .components(
            new Components()
                .addSecuritySchemes(
                    securitySchemeName,
                    new SecurityScheme()
                        .name(securitySchemeName)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    // NOTE: We are NOT applying this to every endpoint since new registrants
    // will not have a token!
  }
}
