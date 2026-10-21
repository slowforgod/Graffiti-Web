package kr.ac.khu.graffiti;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GraffitiApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraffitiApplication.class, args);
    }
}
