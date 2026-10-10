package com.pixelchiss.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties={"eureka.client.enabled=false","EUREKA_URL=http://localhost:8761/eureka/"})
class ApiGatewayApplicationTests {

    @DynamicPropertySource static void publicKey(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.public-key-location", () -> GatewayTestKeys.PUBLIC_KEY);
    }

    @Test
    void contextLoads() {
    }

}
