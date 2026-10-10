package com.pixelchiss.gameservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean public OpenAPI gameOpenApi() {
        return new OpenAPI().info(new Info().title("PixelChiss Game Service").version("1.0"))
                .addServersItem(new Server().url("/").description("Origin of the service or Gateway; paths include /api/game"))
                .components(new Components()
                        .addSecuritySchemes("BearerJWT", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("LobbyToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Lobby-Token")))
                .addSecurityItem(new SecurityRequirement().addList("BearerJWT"));
    }

    @Bean public GroupedOpenApi publicGameApi() {
        return GroupedOpenApi.builder().group("public-game").pathsToMatch("/api/game/**", "/test/**")
                .addOpenApiCustomizer(api -> {
                    Paths publicPaths = new Paths();
                    api.getPaths().forEach((internal, operation) -> {
                        String publicPath = internal.startsWith("/test/") ? "/api/game" + internal : internal;
                        operation.addExtension("x-internal-path", internal);
                        publicPaths.addPathItem(publicPath, operation);
                    });
                    api.setPaths(publicPaths);
                }).build();
    }
}
