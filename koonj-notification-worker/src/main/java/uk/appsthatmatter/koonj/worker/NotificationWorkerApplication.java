package uk.appsthatmatter.koonj.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import uk.appsthatmatter.koonj.notification.NotificationOutbox;

@SpringBootApplication
@EnableScheduling
@EntityScan(basePackageClasses = NotificationOutbox.class)
@EnableJpaRepositories(basePackageClasses = NotificationOutbox.class)
public class NotificationWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationWorkerApplication.class, args);
    }
}
