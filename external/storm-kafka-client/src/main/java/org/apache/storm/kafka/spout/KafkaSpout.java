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

package org.apache.storm.kafka.spout;

import static org.apache.storm.kafka.spout.KafkaSpoutConfig.FirstPollOffsetStrategy.EARLIEST;
import static org.apache.storm.kafka.spout.KafkaSpoutConfig.FirstPollOffsetStrategy.LATEST;
import static org.apache.storm.kafka.spout.KafkaSpoutConfig.FirstPollOffsetStrategy.UNCOMMITTED_EARLIEST;
import static org.apache.storm.kafka.spout.KafkaSpoutConfig.FirstPollOffsetStrategy.UNCOMMITTED_LATEST;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Supplier;
import org.apache.commons.lang.Validate;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.RetriableException;

import org.apache.storm.kafka.spout.KafkaSpoutConfig.FirstPollOffsetStrategy;
import org.apache.storm.kafka.spout.KafkaSpoutConfig.ProcessingGuarantee;
import org.apache.storm.kafka.spout.internal.CommitMetadataManager;
import org.apache.storm.kafka.spout.internal.KafkaConsumerFactory;
import org.apache.storm.kafka.spout.internal.KafkaConsumerFactoryDefault;
import org.apache.storm.kafka.spout.internal.OffsetManager;
import org.apache.storm.kafka.spout.internal.Timer;
import org.apache.storm.kafka.spout.metrics.KafkaOffsetMetric;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KafkaSpout<K, V> extends BaseRichSpout {

    private static final long serialVersionUID = 4151921085047987154L;
    //Initial delay for the commit and subscription refresh timers
    public static final long TIMER_DELAY_MS = 500;
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSpout.class);

    // Storm
    protected SpoutOutputCollector collector;

    // Kafka
    private final KafkaSpoutConfig<K, V> kafkaSpoutConfig;
    private KafkaConsumerFactory<K, V> kafkaConsumerFactory;
    private transient KafkaConsumer<K, V> kafkaConsumer;

    // Bookkeeping
    // Strategy to determine the fetch offset of the first realized by the spout upon activation
    private transient FirstPollOffsetStrategy firstPollOffsetStrategy;
    // Class that has the logic to handle tuple failure.
    private transient KafkaSpoutRetryService retryService;
    // Handles tuple events (emit, ack etc.)
    private transient KafkaTupleListener tupleListener;
    // timer == null only if the processing guarantee is at-most-once
    private transient Timer commitTimer;
    // Initialization is only complete after the first call to  KafkaSpoutConsumerRebalanceListener.onPartitionsAssigned()

    // Tuples that were successfully acked/emitted. These tuples will be committed periodically when the commit timer expires,
    // or after a consumer rebalance, or during close/deactivate. Always empty if processing guarantee is none or at-most-once.
    private transient Map<TopicPartition, OffsetManager> offsetManagers;
    // Tuples that have been emitted but that are "on the wire", i.e. pending being acked or failed.
    // Always empty if processing guarantee is none or at-most-once
    private transient Set<KafkaSpoutMessageId> emitted;
    // Records that have been polled and are queued to be emitted in the nextTuple() call. One record is emitted per nextTuple()
    private transient Map<TopicPartition, List<ConsumerRecord<K, V>>> waitingToEmit;
    // Triggers when a subscription should be refreshed
    private transient Timer refreshSubscriptionTimer;
    private transient TopologyContext context;
    private transient CommitMetadataManager commitMetadataManager;
    private transient KafkaOffsetMetric kafkaOffsetMetric;

    public KafkaSpout(KafkaSpoutConfig<K, V> kafkaSpoutConfig) {
        this(kafkaSpoutConfig, new KafkaConsumerFactoryDefault<K, V>());
    }

    @VisibleForTesting
    KafkaSpout(KafkaSpoutConfig<K, V> kafkaSpoutConfig, KafkaConsumerFactory<K, V> kafkaConsumerFactory) {
        this.kafkaConsumerFactory = kafkaConsumerFactory;
        this.kafkaSpoutConfig = kafkaSpoutConfig;
    }

    @Override
    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        this.context = context;

        // Spout internals
        this.collector = collector;

        // Offset management
        firstPollOffsetStrategy = kafkaSpoutConfig.getFirstPollOffsetStrategy();

        // Retries management
        retryService = kafkaSpoutConfig.getRetryService();

        tupleListener = kafkaSpoutConfig.getTupleListener();

        if (kafkaSpoutConfig.getProcessingGuarantee() != KafkaSpoutConfig.ProcessingGuarantee.AT_MOST_ONCE) {
            // In at-most-once mode the offsets are committed after every poll, and not periodically as controlled by the timer
            commitTimer = new Timer(TIMER_DELAY_MS, kafkaSpoutConfig.getOffsetsCommitPeriodMs(), TimeUnit.MILLISECONDS);
        }
        refreshSubscriptionTimer = new Timer(TIMER_DELAY_MS, kafkaSpoutConfig.getPartitionRefreshPeriodMs(), TimeUnit.MILLISECONDS);

        offsetManagers = new HashMap<>();
        emitted = new HashSet<>();
        waitingToEmit = new HashMap<>();
        commitMetadataManager = new CommitMetadataManager(context, kafkaSpoutConfig.getProcessingGuarantee());

        tupleListener.open(conf, context);
        if (canRegisterMetrics()) {
            registerMetric();
        }

        LOG.info("Kafka Spout opened with the following configuration: {}", kafkaSpoutConfig);
    }

    private void registerMetric() {
        LOG.info("Registering Spout Metrics");
        kafkaOffsetMetric = new KafkaOffsetMetric(new Supplier() {
            @Override
            public Object get() {
                return Collections.unmodifiableMap(offsetManagers);
            }
        }, new Supplier() {
            @Override
            public Object get() {
                return kafkaConsumer;
            }
        });
        context.registerMetric("kafkaOffset", kafkaOffsetMetric, kafkaSpoutConfig.getMetricsTimeBucketSizeInSecs());
    }

    private boolean canRegisterMetrics() {
        try {
            KafkaConsumer.class.getDeclaredMethod("beginningOffsets", Collection.class);
        } catch (NoSuchMethodException e) {
            LOG.warn("Minimum required kafka-clients library version to enable metrics is 0.10.1.0. Disabling spout metrics.");
            return false;
        }
        return true;
    }

    private boolean isAtLeastOnceProcessing() {
        return kafkaSpoutConfig.getProcessingGuarantee() == KafkaSpoutConfig.ProcessingGuarantee.AT_LEAST_ONCE;
    }

    // =========== Consumer Rebalance Listener - On the same thread as the caller ===========
    private class KafkaSpoutConsumerRebalanceListener implements ConsumerRebalanceListener {

        private Collection<TopicPartition> previousAssignment = new HashSet<>();

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            previousAssignment = partitions;

            LOG.info("Partitions revoked. [consumer-group={}, consumer={}, topic-partitions={}]",
                kafkaSpoutConfig.getConsumerGroupId(), kafkaConsumer, partitions);

            if (isAtLeastOnceProcessing()) {
                commitOffsetsForAckedTuples(new HashSet<>(partitions));
            }
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            LOG.info("Partitions reassignment. [task-ID={}, consumer-group={}, consumer={}, topic-partitions={}]",
                context.getThisTaskId(), kafkaSpoutConfig.getConsumerGroupId(), kafkaConsumer, partitions);

            initialize(partitions);
            tupleListener.onPartitionsReassigned(partitions);
        }

        private void initialize(Collection<TopicPartition> partitions) {
            if (isAtLeastOnceProcessing()) {
                // remove offsetManagers for all partitions that are no longer assigned to this spout
                offsetManagers.keySet().retainAll(partitions);
                retryService.retainAll(partitions);

                /*
                 * Emitted messages for partitions that are no longer assigned to this spout can't be acked and should not be retried, hence
                 * remove them from emitted collection.
                 */
                Iterator<KafkaSpoutMessageId> msgIdIterator = emitted.iterator();
                while (msgIdIterator.hasNext()) {
                    KafkaSpoutMessageId msgId = msgIdIterator.next();
                    if (!partitions.contains(msgId.getTopicPartition())) {
                        msgIdIterator.remove();
                    }
                }
            }
            waitingToEmit.keySet().retainAll(partitions);

            Set<TopicPartition> newPartitions = new HashSet<>(partitions);
            newPartitions.removeAll(previousAssignment);
            for (TopicPartition newTp : newPartitions) {
                final OffsetAndMetadata committedOffset = kafkaConsumer.committed(newTp);
                final long fetchOffset = doSeek(newTp, committedOffset);
                LOG.debug("Set consumer position to [{}] for topic-partition [{}] with [{}] and committed offset [{}]",
                    fetchOffset, newTp, firstPollOffsetStrategy, committedOffset);
                // If this partition was previously assigned to this spout, leave the acked offsets as they were to resume where it left off
                if (isAtLeastOnceProcessing() && !offsetManagers.containsKey(newTp)) {
                    offsetManagers.put(newTp, new OffsetManager(newTp, fetchOffset));
                }
            }
            LOG.info("Initialization complete");
        }

        /**
         * Sets the cursor to the location dictated by the first poll strategy and returns the fetch offset.
         */
        private long doSeek(TopicPartition newTp, OffsetAndMetadata committedOffset) {
            LOG.trace("Seeking offset for topic-partition [{}] with [{}] and committed offset [{}]",
                newTp, firstPollOffsetStrategy, committedOffset);

            if (committedOffset != null) {
                // offset was previously committed for this consumer group and topic-partition, either by this or another topology.
                if (commitMetadataManager.isOffsetCommittedByThisTopology(newTp, committedOffset, Collections.unmodifiableMap(offsetManagers))) {
                    // Another KafkaSpout instance (of this topology) already committed, therefore FirstPollOffsetStrategy does not apply.
                    kafkaConsumer.seek(newTp, committedOffset.offset());
                } else {
                    // offset was not committed by this topology, therefore FirstPollOffsetStrategy applies
                    // (only when the topology is first deployed).
                    if (firstPollOffsetStrategy.equals(EARLIEST)) {
                        kafkaConsumer.seekToBeginning(Collections.singleton(newTp));
                    } else if (firstPollOffsetStrategy.equals(LATEST)) {
                        kafkaConsumer.seekToEnd(Collections.singleton(newTp));
                    } else {
                        // Resume polling at the last committed offset, i.e. the first offset that is not marked as processed.
                        kafkaConsumer.seek(newTp, committedOffset.offset());
                    }
                }
            } else {
                // no offset commits have ever been done for this consumer group and topic-partition,
                // so start at the beginning or end depending on FirstPollOffsetStrategy
                if (firstPollOffsetStrategy.equals(EARLIEST) || firstPollOffsetStrategy.equals(UNCOMMITTED_EARLIEST)) {
                    kafkaConsumer.seekToBeginning(Collections.singleton(newTp));
                } else if (firstPollOffsetStrategy.equals(LATEST) || firstPollOffsetStrategy.equals(UNCOMMITTED_LATEST)) {
                    kafkaConsumer.seekToEnd(Collections.singleton(newTp));
                }
            }
            return kafkaConsumer.position(newTp);
        }
    }

    // ======== Next Tuple =======
    @Override
    public void nextTuple() {
        try {
            //??????????????????
            if (refreshSubscriptionTimer.isExpiredResetOnTrue()) {
                kafkaSpoutConfig.getSubscription().refreshAssignment();
            }

            //??????offsetCommitPeriodMs
            if (commitTimer != null && commitTimer.isExpiredResetOnTrue()) {
                if (isAtLeastOnceProcessing()) {
                    //??????acked tuple????????????offset
                    commitOffsetsForAckedTuples(kafkaConsumer.assignment());
                } else if (kafkaSpoutConfig.getProcessingGuarantee() == ProcessingGuarantee.NO_GUARANTEE) {
                    Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = 
                        createFetchedOffsetsMetadata(kafkaConsumer.assignment());
                    kafkaConsumer.commitAsync(offsetsToCommit, null);
                    LOG.debug("Committed offsets {} to Kafka", offsetsToCommit);
                }
            }

            //???????????????????????????partition
            //????????????offset??????maxUncommittedOffsets???partition
            //?????????tuple????????????maxUncommittedOffsets???partition
            PollablePartitionsInfo pollablePartitionsInfo = getPollablePartitionsInfo();
            if (pollablePartitionsInfo.shouldPoll()) {
                try {
                    //???????????????emit?????????
                    setWaitingToEmit(pollKafkaBroker(pollablePartitionsInfo));
                } catch (RetriableException e) {
                    LOG.error("Failed to poll from kafka.", e);
                }
            }

            //emit tuple
            emitIfWaitingNotEmitted();
        } catch (InterruptException e) {
            throwKafkaConsumerInterruptedException();
        }
    }

    private void throwKafkaConsumerInterruptedException() {
        //Kafka throws their own type of exception when interrupted.
        //Throw a new Java InterruptedException to ensure Storm can recognize the exception as a reaction to an interrupt.
        throw new RuntimeException(new InterruptedException("Kafka consumer was interrupted"));
    }

    private PollablePartitionsInfo getPollablePartitionsInfo() {
        if (isWaitingToEmit()) {
            LOG.debug("Not polling. Tuples waiting to be emitted.");
            return new PollablePartitionsInfo(Collections.<TopicPartition>emptySet(), Collections.<TopicPartition, Long>emptyMap());
        }

        //????????????
        Set<TopicPartition> assignment = kafkaConsumer.assignment();
        if (!isAtLeastOnceProcessing()) {
            return new PollablePartitionsInfo(assignment, Collections.<TopicPartition, Long>emptyMap());
        }

        //??????partition?????????????????????offset
        Map<TopicPartition, Long> earliestRetriableOffsets = retryService.earliestRetriableOffsets();
        //???????????????partition????????????offset??????????????????maxUncommittedOffsets?????????
        //????????????offset??????maxUncommittedOffsets
        //?????????tuple????????????maxUncommittedOffsets
        Set<TopicPartition> pollablePartitions = new HashSet<>();
        //????????????????????????????????????offset
        final int maxUncommittedOffsets = kafkaSpoutConfig.getMaxUncommittedOffsets();
        for (TopicPartition tp : assignment) {
            OffsetManager offsetManager = offsetManagers.get(tp);
            int numUncommittedOffsets = offsetManager.getNumUncommittedOffsets();
            if (numUncommittedOffsets < maxUncommittedOffsets) {
                //Allow poll if the partition is not at the maxUncommittedOffsets limit
                pollablePartitions.add(tp);
            } else {
                //??????emit???offset?????????maxUncommittedOffsets???offset
                long offsetAtLimit = offsetManager.getNthUncommittedOffsetAfterCommittedOffset(maxUncommittedOffsets);
                Long earliestRetriableOffset = earliestRetriableOffsets.get(tp);
                if (earliestRetriableOffset != null && earliestRetriableOffset <= offsetAtLimit) {
                    //Allow poll if there are retriable tuples within the maxUncommittedOffsets limit
                    pollablePartitions.add(tp);
                } else {
                    LOG.debug("Not polling on partition [{}]. It has [{}] uncommitted offsets, which exceeds the limit of [{}]. ", tp,
                        numUncommittedOffsets, maxUncommittedOffsets);
                }
            }
        }
        return new PollablePartitionsInfo(pollablePartitions, earliestRetriableOffsets);
    }

    private boolean isWaitingToEmit() {
        for (List<ConsumerRecord<K, V>> value : waitingToEmit.values()) {
            if (!value.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void setWaitingToEmit(ConsumerRecords<K, V> consumerRecords) {
        for (TopicPartition tp : consumerRecords.partitions()) {
            waitingToEmit.put(tp, new ArrayList<>(consumerRecords.records(tp)));
        }
    }

    // ======== poll =========
    //??????kafka??????
    private ConsumerRecords<K, V> pollKafkaBroker(PollablePartitionsInfo pollablePartitionsInfo) {
        //???????????????partition
        doSeekRetriableTopicPartitions(pollablePartitionsInfo.pollableEarliestRetriableOffsets);
        //?????????????????????partition,??????pollablePartitions?????????partition????????????????????????patition?????????????????????????????????tuple???????????????
        Set<TopicPartition> pausedPartitions = new HashSet<>(kafkaConsumer.assignment());
        Iterator<TopicPartition> pausedIter = pausedPartitions.iterator();
        while (pausedIter.hasNext()) {
            if (pollablePartitionsInfo.pollablePartitions.contains(pausedIter.next())) {
                pausedIter.remove();
            }
        }
        try {
            //????????????patition?????????????????????????????????tuple
            kafkaConsumer.pause(pausedPartitions);
            //????????????partition???????????????????????????200ms
            final ConsumerRecords<K, V> consumerRecords = kafkaConsumer.poll(kafkaSpoutConfig.getPollTimeoutMs());
            //ack?????????tuple??????????????????offset????????????tuple???partition
            ackRetriableOffsetsIfCompactedAway(pollablePartitionsInfo.pollableEarliestRetriableOffsets, consumerRecords);
            final int numPolledRecords = consumerRecords.count();
            LOG.debug("Polled [{}] records from Kafka",
                numPolledRecords);
            if (kafkaSpoutConfig.getProcessingGuarantee() == KafkaSpoutConfig.ProcessingGuarantee.AT_MOST_ONCE) {
                //Commit polled records immediately to ensure delivery is at-most-once.
                Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = 
                    createFetchedOffsetsMetadata(kafkaConsumer.assignment());
                kafkaConsumer.commitSync(offsetsToCommit);
                LOG.debug("Committed offsets {} to Kafka", offsetsToCommit);
            }
            return consumerRecords;
        } finally {
            //???????????????partition
            kafkaConsumer.resume(pausedPartitions);
        }
    }

    //?????????????????????partition
    private void doSeekRetriableTopicPartitions(Map<TopicPartition, Long> pollableEarliestRetriableOffsets) {
        for (Entry<TopicPartition, Long> retriableTopicPartitionAndOffset : pollableEarliestRetriableOffsets.entrySet()) {
            //Seek directly to the earliest retriable message for each retriable topic partition
            //???????????????????????????????????????
            kafkaConsumer.seek(retriableTopicPartitionAndOffset.getKey(), retriableTopicPartitionAndOffset.getValue());
        }
    }

    private void ackRetriableOffsetsIfCompactedAway(Map<TopicPartition, Long> earliestRetriableOffsets,
        ConsumerRecords<K, V> consumerRecords) {
        for (Entry<TopicPartition, Long> entry : earliestRetriableOffsets.entrySet()) {
            TopicPartition tp = entry.getKey();
            //????????????????????????
            List<ConsumerRecord<K, V>> records = consumerRecords.records(tp);
            if (!records.isEmpty()) {
                //????????????????????????
                ConsumerRecord<K, V> record = records.get(0);
                //
                long seekOffset = entry.getValue();
                //??????????????????offset
                long earliestReceivedOffset = record.offset();
                //ack partition ???seekOffset???earliestReceivedOffset???message
                if (seekOffset < earliestReceivedOffset) {
                    //Since we asked for tuples starting at seekOffset, some retriable records must have been compacted away.
                    //Ack up to the first offset received if the record is not already acked or currently in the topology
                    for (long i = seekOffset; i < earliestReceivedOffset; i++) {
                        KafkaSpoutMessageId msgId = retryService.getMessageId(new ConsumerRecord<>(tp.topic(), tp.partition(), i, null, null));
                        if (!offsetManagers.get(tp).contains(msgId) && !emitted.contains(msgId)) {
                            LOG.debug("Record at offset [{}] appears to have been compacted away from topic [{}], marking as acked", i, tp);
                            retryService.remove(msgId);
                            emitted.add(msgId);
                            ack(msgId);
                        }
                    }
                }
            }
        }
    }

    // ======== emit  =========
    private void emitIfWaitingNotEmitted() {
        Iterator<List<ConsumerRecord<K, V>>> waitingToEmitIter = waitingToEmit.values().iterator();
        outerLoop:
        while (waitingToEmitIter.hasNext()) {
            List<ConsumerRecord<K, V>> waitingToEmitForTp = waitingToEmitIter.next();
            while (!waitingToEmitForTp.isEmpty()) {
                //emit????????????tuple????????????????????????tuple ??????emit??????retry
                //??????false : 1.??????ack 2.??????emit????????????ack 3.??????failed 4.tuple???null
                //??????emit????????????true
                final boolean emittedTuple = emitOrRetryTuple(waitingToEmitForTp.remove(0));
                if (emittedTuple) {
                    break outerLoop;
                }
            }
            //??????????????????????????????????????????list
            waitingToEmitIter.remove();
        }
    }

    /**
     * Creates a tuple from the kafka record and emits it if it was never emitted or it is ready to be retried.
     * ???kafka ????????????tuple
     * @param record to be emitted
     * @return true if tuple was emitted. False if tuple has been acked or has been emitted and is pending ack or fail
     */
    private boolean emitOrRetryTuple(ConsumerRecord<K, V> record) {
        final TopicPartition tp = new TopicPartition(record.topic(), record.partition());
        //????????????id
        final KafkaSpoutMessageId msgId = retryService.getMessageId(record);

        if (offsetManagers.containsKey(tp) && offsetManagers.get(tp).contains(msgId)) {   // has been acked
            LOG.trace("Tuple for record [{}] has already been acked. Skipping", record);
        } else if (emitted.contains(msgId)) {   // has been emitted and it is pending ack or fail
            LOG.trace("Tuple for record [{}] has already been emitted. Skipping", record);
        } else {
            //????????????
            final OffsetAndMetadata committedOffset = kafkaConsumer.committed(tp);
            if (isAtLeastOnceProcessing()
                && committedOffset != null 
                && committedOffset.offset() > record.offset()
                && commitMetadataManager.isOffsetCommittedByThisTopology(tp, committedOffset, Collections.unmodifiableMap(offsetManagers))) {
                // Ensures that after a topology with this id is started, the consumer fetch
                // position never falls behind the committed offset (STORM-2844)
                throw new IllegalStateException("Attempting to emit a message that has already been committed."
                    + " This should never occur when using the at-least-once processing guarantee.");
            }

            final List<Object> tuple = kafkaSpoutConfig.getTranslator().apply(record);
            if (isEmitTuple(tuple)) {
                final boolean isScheduled = retryService.isScheduled(msgId);
                // not scheduled <=> never failed (i.e. never emitted), or scheduled and ready to be retried
                if (!isScheduled || retryService.isReady(msgId)) {
                    final String stream = tuple instanceof KafkaTuple ? ((KafkaTuple) tuple).getStream() : Utils.DEFAULT_STREAM_ID;

                    if (!isAtLeastOnceProcessing()) {
                        if (kafkaSpoutConfig.isTupleTrackingEnforced()) {
                            collector.emit(stream, tuple, msgId);
                            LOG.trace("Emitted tuple [{}] for record [{}] with msgId [{}]", tuple, record, msgId);
                        } else {
                            collector.emit(stream, tuple);
                            LOG.trace("Emitted tuple [{}] for record [{}]", tuple, record);
                        }
                    } else {
                        emitted.add(msgId);
                        offsetManagers.get(tp).addToEmitMsgs(msgId.offset());
                        if (isScheduled) {  // Was scheduled for retry and re-emitted, so remove from schedule.
                            retryService.remove(msgId);
                        }
                        collector.emit(stream, tuple, msgId);
                        tupleListener.onEmit(tuple, msgId);
                        LOG.trace("Emitted tuple [{}] for record [{}] with msgId [{}]", tuple, record, msgId);
                    }
                    return true;
                }
            } else {
                LOG.debug("Not emitting null tuple for record [{}] as defined in configuration.", record);
                msgId.setEmitted(false);
                ack(msgId);
            }
        }
        return false;
    }

    /**
     * Emits a tuple if it is not a null tuple, or if the spout is configured to emit null tuples.
     */
    private boolean isEmitTuple(List<Object> tuple) {
        return tuple != null || kafkaSpoutConfig.isEmitNullTuples();
    }

    private Map<TopicPartition, OffsetAndMetadata> createFetchedOffsetsMetadata(Set<TopicPartition> assignedPartitions) {
        Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();
        for (TopicPartition tp : assignedPartitions) {
            offsetsToCommit.put(tp, new OffsetAndMetadata(kafkaConsumer.position(tp), commitMetadataManager.getCommitMetadata()));
        }
        return offsetsToCommit;
    }
    
    private void commitOffsetsForAckedTuples(Set<TopicPartition> assignedPartitions) {
        // Find offsets that are ready to be committed for every assigned topic partition
        final Map<TopicPartition, OffsetManager> assignedOffsetManagers = new HashMap<>();
        for (Entry<TopicPartition, OffsetManager> entry : offsetManagers.entrySet()) {
            if (assignedPartitions.contains(entry.getKey())) {
                assignedOffsetManagers.put(entry.getKey(), entry.getValue());
            }
        }

        final Map<TopicPartition, OffsetAndMetadata> nextCommitOffsets = new HashMap<>();
        for (Map.Entry<TopicPartition, OffsetManager> tpOffset : assignedOffsetManagers.entrySet()) {
            final OffsetAndMetadata nextCommitOffset = tpOffset.getValue().findNextCommitOffset(commitMetadataManager.getCommitMetadata());
            if (nextCommitOffset != null) {
                nextCommitOffsets.put(tpOffset.getKey(), nextCommitOffset);
            }
        }

        // Commit offsets that are ready to be committed for every topic partition
        if (!nextCommitOffsets.isEmpty()) {
            kafkaConsumer.commitSync(nextCommitOffsets);
            LOG.debug("Offsets successfully committed to Kafka [{}]", nextCommitOffsets);
            // Instead of iterating again, it would be possible to commit and update the state for each TopicPartition
            // in the prior loop, but the multiple network calls should be more expensive than iterating twice over a small loop
            for (Map.Entry<TopicPartition, OffsetAndMetadata> tpOffset : nextCommitOffsets.entrySet()) {
                //Update the OffsetManager for each committed partition, and update numUncommittedOffsets
                final TopicPartition tp = tpOffset.getKey();
                long position = kafkaConsumer.position(tp);
                long committedOffset = tpOffset.getValue().offset();
                if (position < committedOffset) {
                    /*
                     * The position is behind the committed offset. This can happen in some cases, e.g. if a message failed, lots of (more
                     * than max.poll.records) later messages were acked, and the failed message then gets acked. The consumer may only be
                     * part way through "catching up" to where it was when it went back to retry the failed tuple. Skip the consumer forward
                     * to the committed offset and drop the current waiting to emit list, since it'll likely contain committed offsets.
                     */
                    LOG.debug("Consumer fell behind committed offset. Catching up. Position was [{}], skipping to [{}]",
                        position, committedOffset);
                    kafkaConsumer.seek(tp, committedOffset);
                    List<ConsumerRecord<K, V>> waitingToEmitForTp = waitingToEmit.get(tp);
                    if (waitingToEmitForTp != null) {
                        //Discard the pending records that are already committed
                        List<ConsumerRecord<K, V>> filteredRecords = new ArrayList<>();
                        for (ConsumerRecord<K, V> record : waitingToEmitForTp) {
                            if (record.offset() >= committedOffset) {
                                filteredRecords.add(record);
                            }
                        }
                        waitingToEmit.put(tp, filteredRecords);
                    }
                }

                final OffsetManager offsetManager = assignedOffsetManagers.get(tp);
                offsetManager.commit(tpOffset.getValue());
                LOG.debug("[{}] uncommitted offsets for partition [{}] after commit", offsetManager.getNumUncommittedOffsets(), tp);
            }
        } else {
            LOG.trace("No offsets to commit. {}", this);
        }
    }

    // ======== Ack =======
    @Override
    public void ack(Object messageId) {
        if (!isAtLeastOnceProcessing()) {
            return;
        }

        // Only need to keep track of acked tuples if commits to Kafka are controlled by
        // tuple acks, which happens only for at-least-once processing semantics
        final KafkaSpoutMessageId msgId = (KafkaSpoutMessageId) messageId;
        if (!emitted.contains(msgId)) {
            if (msgId.isEmitted()) {
                LOG.debug("Received ack for message [{}], associated with tuple emitted for a ConsumerRecord that "
                    + "came from a topic-partition that this consumer group instance is no longer tracking "
                    + "due to rebalance/partition reassignment. No action taken.", msgId);
            } else {
                LOG.debug("Received direct ack for message [{}], associated with null tuple", msgId);
            }
        } else {
            //??????????????????????????????
            Validate.isTrue(!retryService.isScheduled(msgId), "The message id " + msgId + " is queued for retry while being acked."
                + " This should never occur barring errors in the RetryService implementation or the spout code.");
            //????????????????????????ack message
            offsetManagers.get(msgId.getTopicPartition()).addToAckMsgs(msgId);
            //emit????????????message
            emitted.remove(msgId);
        }
        //ack message
        tupleListener.onAck(msgId);
    }

    // ======== Fail =======
    @Override
    public void fail(Object messageId) {
        if (!isAtLeastOnceProcessing()) {
            return;
        }
        // Only need to keep track of failed tuples if commits to Kafka are controlled by
        // tuple acks, which happens only for at-least-once processing semantics
        final KafkaSpoutMessageId msgId = (KafkaSpoutMessageId) messageId;
        if (!emitted.contains(msgId)) {
            LOG.debug("Received fail for tuple this spout is no longer tracking."
                + " Partitions may have been reassigned. Ignoring message [{}]", msgId);
            return;
        }
        Validate.isTrue(!retryService.isScheduled(msgId), "The message id " + msgId + " is queued for retry while being failed."
            + " This should never occur barring errors in the RetryService implementation or the spout code.");

        msgId.incrementNumFails();

        if (!retryService.schedule(msgId)) {
            LOG.debug("Reached maximum number of retries. Message [{}] being marked as acked.", msgId);
            // this tuple should be removed from emitted only inside the ack() method. This is to ensure
            // that the OffsetManager for that TopicPartition is updated and allows commit progression
            tupleListener.onMaxRetryReached(msgId);
            ack(msgId);
        } else {
            tupleListener.onRetry(msgId);
            emitted.remove(msgId);
        }
    }

    // ======== Activate / Deactivate / Close / Declare Outputs =======
    @Override
    public void activate() {
        try {
            subscribeKafkaConsumer();
        } catch (InterruptException e) {
            throwKafkaConsumerInterruptedException();
        }
    }

    private void subscribeKafkaConsumer() {
        kafkaConsumer = kafkaConsumerFactory.createConsumer(kafkaSpoutConfig);

        kafkaSpoutConfig.getSubscription().subscribe(kafkaConsumer, new KafkaSpoutConsumerRebalanceListener(), context);
    }

    @Override
    public void deactivate() {
        try {
            shutdown();
        } catch (InterruptException e) {
            throwKafkaConsumerInterruptedException();
        }
    }

    @Override
    public void close() {
        try {
            shutdown();
        } catch (InterruptException e) {
            throwKafkaConsumerInterruptedException();
        }
    }

    private void shutdown() {
        try {
            if (isAtLeastOnceProcessing()) {
                commitOffsetsForAckedTuples(kafkaConsumer.assignment());
            }
        } finally {
            //remove resources
            kafkaConsumer.close();
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        RecordTranslator<K, V> translator = kafkaSpoutConfig.getTranslator();
        for (String stream : translator.streams()) {
            declarer.declareStream(stream, translator.getFieldsFor(stream));
        }
    }

    @Override
    public String toString() {
        return "KafkaSpout{"
            + "offsetManagers =" + offsetManagers
            + ", emitted=" + emitted
            + "}";
    }

    @Override
    public Map<String, Object> getComponentConfiguration() {
        Map<String, Object> configuration = super.getComponentConfiguration();
        if (configuration == null) {
            configuration = new HashMap<>();
        }
        String configKeyPrefix = "config.";

        configuration.put(configKeyPrefix + "topics", getTopicsString());

        configuration.put(configKeyPrefix + "groupid", kafkaSpoutConfig.getConsumerGroupId());
        configuration.put(configKeyPrefix + "bootstrap.servers", kafkaSpoutConfig.getKafkaProps().get("bootstrap.servers"));
        configuration.put(configKeyPrefix + "security.protocol", kafkaSpoutConfig.getKafkaProps().get("security.protocol"));
        return configuration;
    }

    private String getTopicsString() {
        return kafkaSpoutConfig.getSubscription().getTopicsString();
    }

    private static class PollablePartitionsInfo {

        private final Set<TopicPartition> pollablePartitions;
        //The subset of earliest retriable offsets that are on pollable partitions
        private final Map<TopicPartition, Long> pollableEarliestRetriableOffsets;

        public PollablePartitionsInfo(Set<TopicPartition> pollablePartitions, Map<TopicPartition, Long> earliestRetriableOffsets) {
            this.pollablePartitions = pollablePartitions;
            this.pollableEarliestRetriableOffsets = new HashMap<>();
            for (TopicPartition tp : earliestRetriableOffsets.keySet()) {
                //pollablePartitions???????????????partition???????????????pollableEarliestRetriableOffsets
                if (this.pollablePartitions.contains(tp)) {
                    this.pollableEarliestRetriableOffsets.put(tp, earliestRetriableOffsets.get(tp));
                }
            }
        }

        public boolean shouldPoll() {
            return !this.pollablePartitions.isEmpty();
        }
    }

    @VisibleForTesting
    KafkaOffsetMetric getKafkaOffsetMetric() {
        return kafkaOffsetMetric;
    }
}
