package com.example.devicestatus.ingest;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web layer only; Kafka is replaced by a mock, so this test needs no Docker. */
@WebMvcTest(IngestController.class)
class IngestControllerTest {

    private static final String EVENT = """
            {"eventId":"e-1","deviceId":"device-001","capturedAt":"2026-10-16T12:00:00Z",
             "latitude":34.1,"longitude":-118.2,"batteryPercent":80,"status":"ACTIVE"}""";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EventPublisher publisher;

    @Test
    void acceptsSingleEvent() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1));
        verify(publisher).publishAll(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsBatch() throws Exception {
        String batch = "[" + EVENT + "," + EVENT + "," + EVENT + "]";
        mvc.perform(post("/api/events/batch").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(3));
        ArgumentCaptor<List<com.fasterxml.jackson.databind.JsonNode>> captor = ArgumentCaptor.forClass(List.class);
        verify(publisher).publishAll(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(3, captor.getValue().size());
    }

    @Test
    void forwardsEventsWithBadFieldsSoTheStatusServiceCanRecordThem() throws Exception {
        String invalid = "{\"eventId\":\"x\",\"latitude\":999}";
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isAccepted());
        verify(publisher).publishAll(anyList());
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        verify(publisher, never()).publishAll(anyList());
    }

    @Test
    void rejectsArrayOnSingleEndpointAndObjectOnBatchEndpoint() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("[" + EVENT + "]"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/events/batch").contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isBadRequest());
        verify(publisher, never()).publishAll(anyList());
    }

    @Test
    void rejectsOversizedBatch() throws Exception {
        String batch = IntStream.range(0, IngestController.MAX_BATCH_SIZE + 1)
                .mapToObj(i -> EVENT)
                .collect(Collectors.joining(",", "[", "]"));
        mvc.perform(post("/api/events/batch").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isBadRequest());
        verify(publisher, never()).publishAll(anyList());
    }

    @Test
    void returns503WhenKafkaIsDown() throws Exception {
        doThrow(new KafkaUnavailableException("broker down", null)).when(publisher).publishAll(anyList());
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void health() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
