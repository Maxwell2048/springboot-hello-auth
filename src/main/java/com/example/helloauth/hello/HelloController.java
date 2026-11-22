package com.example.helloauth.hello;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Protected by Spring Security: reachable only with a valid Bearer token (see SecurityConfig). */
@RestController
public class HelloController {

    @GetMapping(value = "/api/hello", produces = MediaType.TEXT_PLAIN_VALUE)
    public String hello() {
        return "Hello World";
    }
}
