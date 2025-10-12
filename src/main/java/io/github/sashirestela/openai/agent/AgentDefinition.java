package io.github.sashirestela.openai.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * DTO representing an agent definition loaded from JSON.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDefinition {

    private String id;
    private String name;
    private String model;
    private Double temperature;

    @JsonProperty("response_timeout")
    private Integer responseTimeout;

    @JsonProperty("result_class")
    private String resultClass;

    private Boolean retrieval;

    @JsonProperty("thread_type")
    private String threadType;

    private String instructions;
    private String description;
    private String statut;

    @JsonProperty("openAiId")
    private String openAiId;

    @JsonProperty("openAiAzureIds")
    private List<String> openAiAzureIds;

}
