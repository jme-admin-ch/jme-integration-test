package ch.bit.admin.jeap.jme.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {

    @Value("${test.greeting:hello}")
    private String greeting;

    @GetMapping("/test")
    public String test() {
        return "ok";
    }

    @GetMapping("/greeting")
    public String greeting() {
        return greeting;
    }
}
