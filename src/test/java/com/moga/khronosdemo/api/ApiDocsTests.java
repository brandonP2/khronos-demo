package com.moga.khronosdemo.api;

import com.moga.khronosdemo.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiDocsTests extends IntegrationTest {

    @Test
    void documentsTheNewEndpointsAndTheDeprecatedToggle() throws Exception {
        mockMvc.perform(getJson("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/workers/with-user'].post").exists())
                .andExpect(jsonPath("$.paths['/api/workers/{userId}/active'].put").exists())
                .andExpect(jsonPath("$.paths['/api/users/{id}/password/reset'].put").exists())
                .andExpect(jsonPath("$.paths['/api/users/establishment/{establishmentId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/worker-events/establishment/{establishmentId}'].get.parameters[*].name",
                        hasItem("from")))
                .andExpect(jsonPath("$.paths['/api/worker-shifts/owner/{ownerId}'].get.parameters[*].name", hasItem("to")))
                .andExpect(jsonPath("$.paths['/api/workers/deactivate/{userId}'].delete.deprecated").value(true))
                // The caller is taken from the token, so it must not show up as a parameter
                .andExpect(jsonPath("$.paths['/api/establishments/{id}'].get.parameters[*].name", contains("id")))
                .andExpect(jsonPath("$.paths['/api/workers/with-user'].post.parameters").doesNotExist());
    }
}
