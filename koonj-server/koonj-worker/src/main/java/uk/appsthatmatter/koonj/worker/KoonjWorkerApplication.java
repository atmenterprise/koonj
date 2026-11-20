package uk.appsthatmatter.koonj.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication(scanBasePackages = {
        "uk.appsthatmatter.koonj.worker", "uk.appsthatmatter.koonj.core"
})
public class KoonjWorkerApplication {

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && ("--probe-lock".equals(args[0]) || "--hold-lock".equals(args[0]))) {
            LockProbe.main(args);
            return;
        }
        SpringApplication.run(KoonjWorkerApplication.class, args);
    }
}
