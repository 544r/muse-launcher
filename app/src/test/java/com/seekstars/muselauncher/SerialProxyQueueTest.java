package com.seekstars.muselauncher;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class SerialProxyQueueTest {
    @Test public void appliesOnlyAfterPreviousCompletionAndPropagatesFailure() {
        List<String> starts = new ArrayList<>();
        List<String> results = new ArrayList<>();
        List<SerialProxyQueue.Completion> completions = new ArrayList<>();
        SerialProxyQueue queue = new SerialProxyQueue((value, complete) -> {
            starts.add(value);
            completions.add(complete);
        });
        queue.apply("A", error -> results.add("A:" + error));
        queue.apply("B", error -> results.add("B:" + error));
        queue.apply("C", error -> results.add("C:" + error));
        assertEquals(Arrays.asList("A"), starts);
        assertTrue(results.isEmpty());
        completions.get(0).done(null);
        assertEquals(Arrays.asList("A", "B"), starts);
        completions.get(0).done(null); // 重复回调不得完成下一次请求。
        assertEquals(1, results.size());
        completions.get(1).done("invalid");
        completions.get(2).done(null);
        assertEquals(Arrays.asList("A:null", "B:invalid", "C:null"), results);
    }

    @Test public void supportsSubmittingFromCompletion() {
        List<String> starts = new ArrayList<>();
        List<SerialProxyQueue.Completion> completions = new ArrayList<>();
        SerialProxyQueue queue = new SerialProxyQueue((value, complete) -> {
            starts.add(value); completions.add(complete);
        });
        queue.apply("A", error -> queue.apply("B", ignored -> {}));
        completions.get(0).done(null);
        assertEquals(Arrays.asList("A", "B"), starts);
    }
}
