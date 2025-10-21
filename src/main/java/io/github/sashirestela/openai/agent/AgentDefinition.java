package io.github.sashirestela.openai.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * JSON-deserializable configuration for agent definitions.
 * This class is used to load agent configurations from JSON files
 * and create {@link Agent} instances.
 *
 * <p>Example JSON file format:</p>
 * <pre>{@code
 * {
 *   "id": "101",
 *   "name": "Code Assistant",
 *   "model": "gpt-4o",
 *   "instructions": "You are a helpful coding assistant...",
 *   "resultClass": "CodeResult",
 *   "temperature": 0.7,
 *   "retrieval": false,
 *   "responseTimeout": 120000,
 *   "openAiId": "asst_abc123",
 *   "openAiAzureIds": ["asst_azure1", "asst_azure2"]
 * }
 * }</pre>
 *
 * @see Agent
 * @see AgentService#loadAgentDefinition(String)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDefinition {

    /**
     * Unique identifier for this agent.
     */
    @JsonProperty("id")
    private String id;

    /**
     * Human-readable name for the agent.
     */
    @JsonProperty("name")
    private String name;

    /**
     * Model name (e.g., "gpt-4o", "gpt-4o-mini").
     */
    @JsonProperty("model")
    private String model;

    /**
     * System instructions for the agent.
     */
    @JsonProperty("instructions")
    private String instructions;

    /**
     * Fully qualified class name for structured output mapping.
     * The class should implement {@link AgentResult} interface.
     */
    @JsonProperty("resultClass")
    private String resultClass;

    /**
     * Temperature for response generation (0.0 to 2.0).
     */
    @JsonProperty("temperature")
    private Double temperature;

    /**
     * Whether to enable file search/retrieval capabilities.
     */
    @JsonProperty("retrieval")
    private Boolean retrieval;

    /**
     * Response timeout in milliseconds.
     */
    @JsonProperty("responseTimeout")
    private Integer responseTimeout;

    /**
     * Thread management type.
     */
    @JsonProperty("threadType")
    private String threadType;

    /**
     * Agent type for categorization.
     */
    @JsonProperty("agentType")
    private String agentType;

    /**
     * Whether to create this agent on application startup.
     */
    @JsonProperty("createOnAppStart")
    private Boolean createOnAppStart;

    /**
     * OpenAI Assistant ID (for standard OpenAI API).
     * This field is persisted after agent creation.
     */
    @JsonProperty("openAiId")
    private String openAiId;

    /**
     * OpenAI Assistant IDs for Azure multi-instance deployments.
     * This field is persisted after agent creation.
     */
    @JsonProperty("openAiAzureIds")
    private List<String> openAiAzureIds;

}
