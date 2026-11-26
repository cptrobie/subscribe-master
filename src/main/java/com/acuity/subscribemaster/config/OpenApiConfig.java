package com.acuity.subscribemaster.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI documentation (NFR-09, via springdoc), split into one spec per audience (NFR-46):
 *
 * <ul>
 *   <li>{@code customer}: every {@code /api/v1/**} path except {@code /api/v1/staff/**}, served at
 *       {@code /v3/api-docs/customer};
 *   <li>{@code staff}: {@code /api/v1/staff/**}, served at {@code /v3/api-docs/staff}.
 * </ul>
 *
 * <p>The split mirrors FR-06's two security chains, and Swagger UI's "Select a definition" dropdown
 * switches between the two. The combined {@code /v3/api-docs} is turned off in {@code
 * application.yaml} ({@code springdoc.enable-default-api-docs: false}), so no spec mixes the two
 * audiences.
 *
 * <p>The {@link OpenAPI} bean holds what both specs share: version, contact and license. springdoc
 * gives each group its own copy of it, and the group's customizer sets that copy's title and
 * description and adds a {@value #BEARER_SCHEME} HTTP bearer scheme. The scheme's description names
 * the login that issues the token, because a customer token is rejected on staff endpoints and the
 * reverse. The scheme has the same name in both groups, so a controller marks its protected
 * endpoints with {@code @SecurityRequirement(name = "bearerAuth")} whichever group it is in. Login,
 * registration and verification endpoints don't carry it, since they're called without a token.
 */
@Configuration
class OpenApiConfig {

  /** Name of each group's bearer scheme, used by {@code @SecurityRequirement} on controllers. */
  static final String BEARER_SCHEME = "bearerAuth";

  @Bean
  OpenAPI subscribeMasterOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .version("0.0.1-SNAPSHOT")
                .contact(new Contact().name("API Support Team").email("support@example.com"))
                .license(new License().name("Apache 2.0").url("https://apache.org")))
        .components(new Components());
  }

  @Bean
  GroupedOpenApi customerApi() {
    return GroupedOpenApi.builder()
        .displayName("Customer API")
        .group("customer")
        .pathsToMatch("/api/v1/**")
        .pathsToExclude("/api/v1/staff/**")
        .addOpenApiCustomizer(
            openApi ->
                applyAudience(
                    openApi,
                    "Subscribe Master Customer API",
                    "Customer account and subscription endpoints. Registration, "
                        + "email verification and login under /api/v1/auth need no token; every "
                        + "other endpoint needs a customer token.",
                    "Customer JWT from POST /api/v1/auth/login. A staff token is rejected "
                        + "with 401."))
        .build();
  }

  @Bean
  GroupedOpenApi staffApi() {
    return GroupedOpenApi.builder()
        .displayName("Staff API")
        .group("staff")
        .pathsToMatch("/api/v1/staff/**")
        .addOpenApiCustomizer(
            openApi ->
                applyAudience(
                    openApi,
                    "Subscribe Master Staff API",
                    "Back-office endpoints for staff. Login needs no token; every other endpoint "
                        + "needs a staff token, and the caller's role must hold the permission the endpoint "
                        + "requires (403 otherwise).",
                    "Staff JWT from POST /api/v1/staff/auth/login. A customer token is rejected with 401."))
        .build();
  }

  private static void applyAudience(
      OpenAPI openApi, String title, String description, String tokenDescription) {
    openApi.getInfo().title(title).description(description);
    openApi
        .getComponents()
        .addSecuritySchemes(
            BEARER_SCHEME,
            new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description(tokenDescription));
  }
}
