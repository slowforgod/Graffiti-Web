package kr.ac.khu.graffiti.user;

import java.security.SecureRandom;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class UserConfig {

    @Bean
    NicknameGenerator nicknameGenerator(GraffitiProperties props) {
        return new NicknameGenerator(new SecureRandom(), props.doodle().palette());
    }
}
