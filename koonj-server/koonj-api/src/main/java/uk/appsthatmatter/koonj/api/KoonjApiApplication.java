package uk.appsthatmatter.koonj.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication(scanBasePackages = {
        "uk.appsthatmatter.koonj.api", "uk.appsthatmatter.koonj.core"
})
public class KoonjApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(KoonjApiApplication.class, args);
    }
}
