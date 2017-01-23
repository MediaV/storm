package org.apache.storm.kafka.spout;

import org.apache.kafka.common.TopicPartition;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Created by liurenjie on 23/01/2017.
 */
public class KafkaSpoutMessageIdTest {
    @Test
    public void testEquals() {
        KafkaSpoutMessageId id1 = new KafkaSpoutMessageId(new TopicPartition("xx", 1), 1);
        KafkaSpoutMessageId id2 = new KafkaSpoutMessageId(new TopicPartition("xx", 1), 1);

        Set<KafkaSpoutMessageId> emitted = new HashSet<>();
        emitted.add(id1);

        assertTrue(emitted.contains(id2));
    }
}
