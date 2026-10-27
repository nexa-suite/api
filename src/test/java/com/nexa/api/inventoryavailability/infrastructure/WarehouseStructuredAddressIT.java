package com.nexa.api.inventoryavailability.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class WarehouseStructuredAddressIT extends NexaWorkflowIntegrationSupport {
    @Test
    void addressDetailsRoundTripAndLegacyUpdatesPreserveThem() throws Exception {
        String auth = "Bearer " + accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        var created = mockMvc.perform(post("/api/v1/warehouses").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"ADDR-" + UUID.randomUUID().toString().substring(0, 8)
                                + "\",\"name\":\"Synthetic address warehouse\",\"address\":\"Test street 100\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = json(created).get("id").asText();
        mockMvc.perform(post("/api/v1/warehouses/" + id + "/access-grants")
                        .header("Authorization", "Bearer " + accessToken(OWNER_EMAIL, "PLATFORM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + membershipId(WAREHOUSE_EMAIL) + "\"}"))
                .andExpect(status().isOk());
        auth = "Bearer " + accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String details = "{\"countryCode\":\"PE\",\"departmentCode\":\"15\",\"provinceCode\":\"1501\",\"districtCode\":\"150101\",\"city\":\"Lima\",\"roadType\":\"STREET\",\"streetName\":\"Synthetic Test Street\",\"streetNumber\":\"100\",\"interior\":\"B\",\"postalCode\":\"15001\",\"reference\":\"Synthetic test reference\"}";
        var updated = mockMvc.perform(patch("/api/v1/warehouses/" + id + "/location").header("Authorization", auth)
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"addressDetails\":" + details + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.addressDetails.postalCode").value("15001"))
                .andExpect(jsonPath("$.addressDetails.streetNumber").value("100")).andReturn();
        mockMvc.perform(get("/api/v1/warehouses/" + id + "/location").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.addressDetails.districtCode").value("150101"));
        var legacy = mockMvc.perform(patch("/api/v1/warehouses/" + id + "/location").header("Authorization", auth)
                        .header("If-Match", updated.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"address\":\"Updated synthetic summary\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.addressDetails.countryCode").value("PE"))
                .andExpect(jsonPath("$.addressDetails.reference").value("Synthetic test reference")).andReturn();
        mockMvc.perform(patch("/api/v1/warehouses/" + id + "/location").header("Authorization", auth)
                        .header("If-Match", legacy.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"addressDetails\":" + details.replace("150101", "080101") + "}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/warehouses/" + id + "/location").header("Authorization", auth)
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"addressDetails\":" + details + "}"))
                .andExpect(status().isPreconditionFailed());
    }
}
