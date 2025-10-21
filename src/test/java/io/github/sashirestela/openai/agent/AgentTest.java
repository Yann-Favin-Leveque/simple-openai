package io.github.sashirestela.openai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Agent} POJO.
 */
class AgentTest {

    @Test
    void testAgentBuilder() {
        var agent = Agent.builder()
                .id("101")
                .name("Test Agent")
                .model("gpt-4o")
                .instructions("Test instructions")
                .resultClass("com.example.TestResult")
                .temperature(0.7)
                .retrieval(true)
                .responseTimeout(60000L)
                .threadType("THREAD_UNIQUE_PER_USERID")
                .agentType("CHAT")
                .openAiId("asst_123")
                .openAiAzureIds(List.of("asst_azure_1", "asst_azure_2"))
                .status("active")
                .threadId("thread_456")
                .createOnAppStart(true)
                .build();

        assertEquals("101", agent.getId());
        assertEquals("Test Agent", agent.getName());
        assertEquals("gpt-4o", agent.getModel());
        assertEquals("Test instructions", agent.getInstructions());
        assertEquals("com.example.TestResult", agent.getResultClass());
        assertEquals(0.7, agent.getTemperature());
        assertTrue(agent.getRetrieval());
        assertEquals(60000L, agent.getResponseTimeout());
        assertEquals("THREAD_UNIQUE_PER_USERID", agent.getThreadType());
        assertEquals("CHAT", agent.getAgentType());
        assertEquals("asst_123", agent.getOpenAiId());
        assertEquals(List.of("asst_azure_1", "asst_azure_2"), agent.getOpenAiAzureIds());
        assertEquals("active", agent.getStatus());
        assertEquals("thread_456", agent.getThreadId());
        assertTrue(agent.getCreateOnAppStart());
    }

    @Test
    void testAgentNoArgsConstructor() {
        var agent = new Agent();
        assertNull(agent.getId());
        assertNull(agent.getName());
        assertNull(agent.getModel());
    }

    @Test
    void testAgentAllArgsConstructor() {
        // Order: id, name, openAiId, openAiAzureIds, model, instructions, resultClass, temperature,
        //        threadType, status, threadId, responseTimeout, retrieval, agentType, createOnAppStart
        var agent = new Agent(
                "101",                           // id
                "Test Agent",                    // name
                "asst_123",                      // openAiId
                List.of("asst_azure_1"),         // openAiAzureIds
                "gpt-4o",                        // model
                "Test instructions",             // instructions
                "com.example.TestResult",        // resultClass
                0.7,                             // temperature
                "THREAD_UNIQUE_PER_USERID",      // threadType
                "active",                        // status
                "thread_456",                    // threadId
                60000L,                          // responseTimeout
                true,                            // retrieval
                "CHAT",                          // agentType
                true                             // createOnAppStart
        );

        assertEquals("101", agent.getId());
        assertEquals("Test Agent", agent.getName());
    }

    @Test
    void testAgentSettersAndGetters() {
        var agent = new Agent();

        agent.setId("202");
        agent.setName("Updated Agent");
        agent.setModel("gpt-4o-mini");
        agent.setInstructions("Updated instructions");
        agent.setResultClass("UpdatedResult");
        agent.setTemperature(0.5);
        agent.setRetrieval(false);
        agent.setResponseTimeout(30000L);
        agent.setThreadType("THREAD_UNIQUE_PER_CONVERSATIONID");
        agent.setAgentType("TASK");
        agent.setOpenAiId("asst_456");
        agent.setOpenAiAzureIds(List.of("asst_azure_3"));
        agent.setStatus("inactive");
        agent.setThreadId("thread_789");
        agent.setCreateOnAppStart(true);

        assertEquals("202", agent.getId());
        assertEquals("Updated Agent", agent.getName());
        assertEquals("gpt-4o-mini", agent.getModel());
        assertEquals("Updated instructions", agent.getInstructions());
        assertEquals("UpdatedResult", agent.getResultClass());
        assertEquals(0.5, agent.getTemperature());
        assertFalse(agent.getRetrieval());
        assertEquals(30000L, agent.getResponseTimeout());
        assertEquals("THREAD_UNIQUE_PER_CONVERSATIONID", agent.getThreadType());
        assertEquals("TASK", agent.getAgentType());
        assertEquals("asst_456", agent.getOpenAiId());
        assertEquals(List.of("asst_azure_3"), agent.getOpenAiAzureIds());
        assertEquals("inactive", agent.getStatus());
        assertEquals("thread_789", agent.getThreadId());
        assertTrue(agent.getCreateOnAppStart());
    }

    @Test
    void testAgentMinimalConfiguration() {
        var agent = Agent.builder()
                .id("303")
                .model("gpt-4o")
                .build();

        assertEquals("303", agent.getId());
        assertEquals("gpt-4o", agent.getModel());
        assertNull(agent.getName());
        assertNull(agent.getInstructions());
        assertNull(agent.getTemperature());
    }

    @Test
    void testAgentEqualsAndHashCode() {
        var agent1 = Agent.builder()
                .id("101")
                .name("Test Agent")
                .build();

        var agent2 = Agent.builder()
                .id("101")
                .name("Test Agent")
                .build();

        assertEquals(agent1, agent2);
        assertEquals(agent1.hashCode(), agent2.hashCode());
    }

    @Test
    void testAgentToString() {
        var agent = Agent.builder()
                .id("101")
                .name("Test Agent")
                .model("gpt-4o")
                .build();

        String toString = agent.toString();
        assertTrue(toString.contains("101"));
        assertTrue(toString.contains("Test Agent"));
        assertTrue(toString.contains("gpt-4o"));
    }

}
