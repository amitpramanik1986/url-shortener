package io.amitpramanik1986.url.shortener;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
class UrlShortenerApplication {

    static void main(String[] args) {
		SpringApplication.run(UrlShortenerApplication.class, args);
	}

    @Bean
    OpenAPI customOpenAPI(@Value("${spring.application.name}") String appName){
        return new OpenAPI()
                .info(new Info()
                        .contact(new Contact().email("amitpramanik.mca@gmail.com"))
                        .title(appName)
                        .description("Demo on Spring boot with Kafka Consumer")
                        .termsOfService("http://swagger.io/terms/")
                        .license(new License().name("Apache 2.0")
                                .url("http://springdoc.org"))
                );
    }
}
