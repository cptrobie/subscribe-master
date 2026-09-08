package com.acuity.subscribemaster.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI subscribeMasterOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Subscribe Master API")
                        .description("Subscription management, payments, and billing API")
                        .version("0.0.1-SNAPSHOT")
                        .contact(new Contact()
                                .name("API Support Team")
                                .email("support@example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://apache.org")));
    }
}