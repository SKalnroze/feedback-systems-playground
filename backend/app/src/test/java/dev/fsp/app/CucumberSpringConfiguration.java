package dev.fsp.app;

import dev.fsp.app.support.PostgresSupport;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Boots the application once for the whole acceptance suite. */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresSupport.class)
@ActiveProfiles("test")
public class CucumberSpringConfiguration {
}
