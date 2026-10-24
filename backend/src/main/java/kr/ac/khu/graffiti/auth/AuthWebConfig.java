package kr.ac.khu.graffiti.auth;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class AuthWebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserResolver;
    private final ApiGuards guards;

    AuthWebConfig(CurrentUserArgumentResolver currentUserResolver, ApiGuards guards) {
        this.currentUserResolver = currentUserResolver;
        this.guards = guards;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guards.admin()).addPathPatterns("/api/admin/**");
        registry.addInterceptor(guards.writes()).addPathPatterns("/api/**").excludePathPatterns("/api/admin/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserResolver);
    }
}
