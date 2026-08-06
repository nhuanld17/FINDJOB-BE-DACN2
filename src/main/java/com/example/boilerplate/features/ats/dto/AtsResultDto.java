package com.example.boilerplate.features.ats.dto;

import java.util.List;

public record AtsResultDto(
        int overallScore,
        List<String> matchedSkills,
        List<String> missingSkills,
        String semanticReasoning,
        List<String> tips,
        int cvTextLength,
        String provider,
        String model,
        boolean cached
) {}