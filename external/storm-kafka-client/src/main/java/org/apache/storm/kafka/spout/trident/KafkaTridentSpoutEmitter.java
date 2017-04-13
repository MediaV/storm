/*
 * Licensed to the Apache Software Foundation (ASF) under one
 *   or more contributor license agreements.  See the NOTICE file
 *   distributed with this work for additional information
 *   regarding copyright ownership.  The ASF licenses this file
 *   to you under the Apache License, Version 2.0 (the
 *   "License"); you may not use this file except in compliance
 *   with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.apache.storm.kafka.spout.trident;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.storm.kafka.spout.KafkaSpoutConfig;
import org.apache.storm.kafka.spout.RecordTranslator;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.trident.operation.TridentCollector;
import org.apache.storm.trident.spout.IOpaquePartitionedTridentSpout;
import org.apache.storm.trident.topology.TransactionAttempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;

import static org.apache.storm.kafka.spout.KafkaSpoutConfig.FirstPollOffsetStrategy.*;

public class KafkaTridentSpoutEmitter<K, V> implements IOpaquePartitionedTridentSpout.Emitter<
        List<TopicPartition>,
        KafkaTridentSpoutTopicPartition,
        KafkaTridentSpoutBatchMetadata<K, V>>,
        Serializable {

    private static final long serialVersionUID = -7343927794834130435L;
    private static final Logger LOG = LoggerFactory.getLogger(KafkaTridentSpoutEmitter.class);

    // Kafka
    private final KafkaConsumer<K, V> kafkaConsumer;

    // Bookkeeping
    private final KafkaTridentSpoutManager<K, V> kafkaManager;

    // Declare some KafkaTridentSpoutManager references for convenience
    private final long pollTimeoutMs;
    private final KafkaSpoutConfig.FirstPollOffsetStrategy firstPollOffsetStrategy;
    private final RecordTranslator<K, V> translator;

    private TopologyContext topologyContext;

    public KafkaTridentSpoutEmitter(KafkaTridentSpoutManager<K, V> kafkaManager, TopologyContext topologyContext) {
        this.kafkaConsumer = kafkaManager.createKafkaConsumer();
        this.kafkaManager = kafkaManager;
        this.topologyContext = topologyContext;
        this.translator = kafkaManager.getKafkaSpoutConfig().getTranslator();

        final KafkaSpoutConfig<K, V> kafkaSpoutConfig = kafkaManager.getKafkaSpoutConfig();
        this.pollTimeoutMs = kafkaSpoutConfig.getPollTimeoutMs();
        this.firstPollOffsetStrategy = kafkaSpoutConfig.getFirstPollOffsetStrategy();
        LOG.debug("Created {}", this);
    }

    @Override
    public KafkaTridentSpoutBatchMetadata<K, V> emitPartitionBatch(TransactionAttempt tx, TridentCollector collector,
                                                                   KafkaTridentSpoutTopicPartition currBatchPartition, KafkaTridentSpoutBatchMetadata<K, V> lastBatch) {

        LOG.debug("Processing batch: [transaction = {}], [currBatchPartition = {}], [lastBatchMetadata = {}], [collector = {}]",
                tx, currBatchPartition, lastBatch, collector);

        final TopicPartition currBatchTp = currBatchPartition.getTopicPartition();

        kafkaConsumer.assign(Collections.singleton(currBatchTp));
        KafkaTridentSpoutBatchMetadata<K, V> currentBatch = lastBatch;


        try {
            seek(currBatchTp, lastBatch);

            final ConsumerRecords<K, V> records = kafkaConsumer.poll(pollTimeoutMs);
            LOG.debug("Polled [{}] records from Kafka.", records.count());

            if (!records.isEmpty()) {
                emitTuples(collector, records);
                // build new metadata
                currentBatch = new KafkaTridentSpoutBatchMetadata<>(currBatchTp, records, lastBatch);
            }
        } catch (KafkaException e) {
            LOG.error("Failed to fetch records.", e);
        }
        LOG.debug("Emitted batch: [transaction = {}], [currBatchPartition = {}], [lastBatchMetadata = {}], " +
                "[currBatchMetadata = {}], [collector = {}]", tx, currBatchPartition, lastBatch, currentBatch, collector);

        return currentBatch;
    }

    private void emitTuples(TridentCollector collector, ConsumerRecords<K, V> records) {
        for (ConsumerRecord<K, V> record : records) {
            final List<Object> tuple = translator.apply(record);
            collector.emit(tuple);
            LOG.debug("Emitted tuple {} for record [{}]", tuple, record);
        }
    }

    /**
     * Determines the offset of the next fetch. For failed batches lastBatchMeta is not null and contains the fetch
     * offset of the failed batch. In this scenario the next fetch will take place at offset of the failed batch + 1.
     * When the previous batch is successful, lastBatchMeta is null, and the offset of the next fetch is either the
     * offset of the last commit to kafka, or if no commit was yet made, the offset dictated by
     * {@link KafkaSpoutConfig.FirstPollOffsetStrategy}
     *
     * @return the offset of the next fetch
     */
    private long seek(TopicPartition tp, KafkaTridentSpoutBatchMetadata<K, V> lastBatchMeta) {
        if (lastBatchMeta != null) {
            kafkaConsumer.seek(tp, lastBatchMeta.getLastOffset() + 1);  // seek next offset after last offset from previous batch
            LOG.debug("Seeking fetch offset to next offset after last offset from previous batch for topic-partition [{}]", tp);
        } else {
            LOG.debug("Seeking fetch offset from firstPollOffsetStrategy and last commit to Kafka for topic-partition [{}]", tp);
            if (firstPollOffsetStrategy.equals(EARLIEST) || firstPollOffsetStrategy.equals(UNCOMMITTED_EARLIEST)) {
                kafkaConsumer.seekToBeginning(Collections.singleton(tp));
            } else if (firstPollOffsetStrategy.equals(LATEST) || firstPollOffsetStrategy.equals(UNCOMMITTED_LATEST)) {
                kafkaConsumer.seekToEnd(Collections.singleton(tp));
            }
        }
        final long fetchOffset = kafkaConsumer.position(tp);
        LOG.debug("Set [fetchOffset = {}]", fetchOffset);
        return fetchOffset;
    }

    @Override
    public void refreshPartitions(List<KafkaTridentSpoutTopicPartition> partitionResponsibilities) {
        LOG.trace("Refreshing of topic-partitions handled by Kafka. " +
                "No action taken by this method for topic partitions {}", partitionResponsibilities);
    }

    /**
     * Computes ordered list of topic-partitions for this task taking into consideration that topic-partitions
     * for this task must be assigned to the Kafka consumer running on this task.
     *
     * @param allPartitionInfo list of all partitions as returned by {@link KafkaTridentSpoutOpaqueCoordinator}
     * @return ordered list of topic partitions for this task
     */
    @Override
    public List<KafkaTridentSpoutTopicPartition> getOrderedPartitions(final List<TopicPartition> allPartitionInfo) {
        final List<KafkaTridentSpoutTopicPartition> allPartitions = newKafkaTridentSpoutTopicPartitions(allPartitionInfo);
        LOG.debug("Returning all topic-partitions {} across all tasks. Current task index [{}]. Total tasks [{}] ",
                allPartitions, topologyContext.getThisTaskIndex(), getNumTasks());
        return allPartitions;
    }

    @Override
    public List<KafkaTridentSpoutTopicPartition> getPartitionsForTask(int taskId, int numTasks, List<TopicPartition> allPartitionInfo) {
        final Set<TopicPartition> assignedTps = kafkaConsumer.assignment();
        LOG.debug("Consumer [{}], running on task with index [{}], has assigned topic-partitions {}", kafkaConsumer, taskId, assignedTps);
        final List<KafkaTridentSpoutTopicPartition> taskTps = newKafkaTridentSpoutTopicPartitions(assignedTps);
        LOG.debug("Returning topic-partitions {} for task with index [{}]", taskTps, taskId);
        return taskTps;
    }

    private List<KafkaTridentSpoutTopicPartition> newKafkaTridentSpoutTopicPartitions(Collection<TopicPartition> tps) {
        final List<KafkaTridentSpoutTopicPartition> kttp = new ArrayList<>(tps == null ? 0 : tps.size());
        if (tps != null) {
            for (TopicPartition tp : tps) {
                LOG.trace("Added topic-partition [{}]", tp);
                kttp.add(new KafkaTridentSpoutTopicPartition(tp));
            }
        }
        return kttp;
    }

    private int getNumTasks() {
        return topologyContext.getComponentTasks(topologyContext.getThisComponentId()).size();
    }

    @Override
    public void close() {
        kafkaConsumer.close();
        LOG.debug("Closed");
    }

    @Override
    public String toString() {
        return super.toString() +
                "{kafkaManager=" + kafkaManager +
                '}';
    }
}
