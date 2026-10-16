package in.srminsider.labs.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/adminlogin").setViewName("forward:/admin/adminlogin.html");
        registry.addViewController("/adminlogin.html").setViewName("forward:/admin/adminlogin.html");
    }
}
