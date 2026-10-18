package com.example.devicestatus.status.processing;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/** In-memory "processed since startup" counters shown on the dashboard. Reset on restart. */
@Component
public class OutcomeCounters {

    private final Map<Outcome, LongAdder> counters = new EnumMap<>(Outcome.class);

    public OutcomeCounters() {
        for (Outcome o : Outcome.values()) {
            counters.put(o, new LongAdder());
        }
    }

    public void record(Outcome outcome) {
        counters.get(outcome).increment();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        counters.forEach((outcome, adder) -> result.put(outcome.name(), adder.sum()));
        return result;
    }
}
