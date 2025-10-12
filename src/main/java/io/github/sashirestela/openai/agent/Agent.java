package io.github.sashirestela.openai.agent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Represents an AI agent (OpenAI/Azure Assistant).
 * This is a simple POJO, not a JPA entity.
 */
@Data
@Builder
public class Agent {

    private String id;
    private String name;
    private String openAiId;
    private List<String> openAiAzureIds;
    private String model;
    private String instructions;
    private String resultClass;
    private Double temperature;
    private String threadType;
    private String statut;
    private String threadId;
    private Long responseTimeout;
    @Builder.Default
    private Boolean retrieval = false;
    private String agentType;
    @Builder.Default
    private Boolean createOnAppStart = false;

}
