package com.netpulse.integration;

import com.jayway.jsonpath.JsonPath;
import com.netpulse.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeviceApiIT extends IntegrationTestBase {
    @Autowired MockMvc mvc;
    String admin;

    @BeforeEach void setUp() {
        jdbc.update("delete from device");
        admin = tokenFor(Role.ADMIN);
    }

    private ResultActions create(String json) throws Exception {
        return mvc.perform(post("/api/v1/devices").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String device(String name, String host) {
        return "{\"name\":\"%s\",\"host\":\"%s\",\"type\":\"SERVER\",\"tags\":[\"Core\"],\"ports\":[{\"port\":443,\"label\":\"https\"}]}".formatted(name, host);
    }

    private String id(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test void createReturnsUnknownStatusAndNormalizedTag() throws Exception {
        create(device("web-1", "8.8.8.8")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.tags[0]").value("core"))
                .andExpect(jsonPath("$.checkIntervalSeconds").value(60));
    }

    @Test void publicTargetsAreAllowedForSingleHostMonitoring() throws Exception {
        create(device("google-dns", "8.8.8.8")).andExpect(status().isCreated());
        create(device("site", "example.com")).andExpect(status().isCreated());
    }

    @Test void validationErrorsAreProblemDetailsWithFieldMap() throws Exception {
        create("{\"name\":\"\",\"host\":\"\",\"type\":\"SERVER\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.host").exists());
    }

    @Test void invalidAndSpecialHostsRejected() throws Exception {
        create(device("bad", "224.0.0.1")).andExpect(status().isBadRequest());
        create(device("bad2", "1.2.3.999")).andExpect(status().isBadRequest());
        create(device("bad3", "x; rm -rf /")).andExpect(status().isBadRequest());
    }

    @Test void portOutOfRangeRejected() throws Exception {
        create("{\"name\":\"p\",\"host\":\"8.8.8.8\",\"type\":\"SERVER\",\"ports\":[{\"port\":70000}]}").andExpect(status().isBadRequest());
    }

    @Test void duplicatePortRejected() throws Exception {
        create("{\"name\":\"p\",\"host\":\"8.8.8.8\",\"type\":\"SERVER\",\"ports\":[{\"port\":80},{\"port\":80}]}").andExpect(status().isBadRequest());
    }

    @Test void duplicateNameIsConflictCaseInsensitive() throws Exception {
        create(device("web-1", "8.8.8.8")).andExpect(status().isCreated());
        create(device("WEB-1", "8.8.4.4")).andExpect(status().isConflict());
    }

    @Test void dnsCheckValidation() throws Exception {
        create("{\"name\":\"d\",\"host\":\"8.8.8.8\",\"type\":\"SERVER\",\"dnsChecks\":[{\"recordType\":\"A\"}]}").andExpect(status().isBadRequest());
        create("{\"name\":\"d\",\"host\":\"8.8.8.8\",\"type\":\"SERVER\",\"dnsChecks\":[{\"recordType\":\"PTR\"}]}").andExpect(status().isCreated());
    }

    @Test void updateKeepingSamePortDoesNotViolateUniqueConstraint() throws Exception {
        String id = id(create(device("web-1", "8.8.8.8")));
        mvc.perform(put("/api/v1/devices/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"web-1\",\"host\":\"8.8.8.8\",\"type\":\"SERVER\",\"ports\":[{\"port\":443,\"label\":\"tls\"},{\"port\":22}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ports", hasSize(2)));
    }

    @Test void listSupportsSearchFilterSortAndPaging() throws Exception {
        create(device("alpha", "8.8.8.8"));
        create("{\"name\":\"beta\",\"host\":\"example.com\",\"type\":\"WEBSITE\"}");
        mvc.perform(get("/api/v1/devices").header("Authorization", admin).param("q", "alp"))
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.page.totalElements").value(1));
        mvc.perform(get("/api/v1/devices").header("Authorization", admin).param("type", "WEBSITE"))
                .andExpect(jsonPath("$.content[0].name").value("beta"));
        mvc.perform(get("/api/v1/devices").header("Authorization", admin).param("tag", "core"))
                .andExpect(jsonPath("$.content[0].name").value("alpha"));
        mvc.perform(get("/api/v1/devices").header("Authorization", admin).param("sort", "name,desc"))
                .andExpect(jsonPath("$.content[0].name").value("beta"));
    }

    @Test void searchTreatsLikeWildcardsLiterally() throws Exception {
        create(device("alpha", "8.8.8.8"));
        mvc.perform(get("/api/v1/devices").header("Authorization", admin).param("q", "%"))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test void sortingByUnknownFieldIsRejected() throws Exception {
        mvc.perform(get("/api/v1/devices").header("Authorization", admin).param("sort", "passwordHash,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test void pauseAndResumeRecordEvents() throws Exception {
        String id = id(create(device("web-1", "8.8.8.8")));
        mvc.perform(post("/api/v1/devices/" + id + "/pause").header("Authorization", admin))
                .andExpect(jsonPath("$.status").value("PAUSED")).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/v1/devices/" + id + "/check-now").header("Authorization", admin)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/devices/" + id + "/resume").header("Authorization", admin))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
        mvc.perform(get("/api/v1/devices/" + id + "/events").header("Authorization", admin))
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test void bulkDelete() throws Exception {
        String a = id(create(device("a", "8.8.8.8")));
        String b = id(create(device("b", "8.8.4.4")));
        mvc.perform(post("/api/v1/devices/bulk").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"%s\",\"%s\"],\"action\":\"DELETE\"}".formatted(a, b)))
                .andExpect(jsonPath("$.affected").value(2));
        mvc.perform(get("/api/v1/devices/" + a).header("Authorization", admin)).andExpect(status().isNotFound());
    }

    @Test void viewerCanReadButNotWrite() throws Exception {
        String viewer = tokenFor(Role.VIEWER);
        mvc.perform(get("/api/v1/devices").header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/devices").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
                .content(device("x", "8.8.8.8"))).andExpect(status().isForbidden());
    }

    @Test void badUuidIsClientError() throws Exception {
        mvc.perform(get("/api/v1/devices/not-a-uuid").header("Authorization", admin)).andExpect(status().is4xxClientError());
    }
}
