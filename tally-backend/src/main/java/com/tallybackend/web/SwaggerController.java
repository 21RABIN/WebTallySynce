package com.tallybackend.web;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Hidden
@Controller
public class SwaggerController {

    @GetMapping({"/swagger", "/swagger/"})
    public String swagger() {
        return "redirect:/swagger-ui/index.html";
    }
}
