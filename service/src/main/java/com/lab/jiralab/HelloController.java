package com.lab.jiralab;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "ok",
                "service", "jira-lab",
                "message", "Change this line on a feature branch whose name contains the Jira key.");
    }
}
