/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.mailbox.cassandra.mail;

import static org.apache.james.backends.cassandra.init.configuration.CassandraConsistenciesConfiguration.ConsistencyChoice;
import static org.apache.james.backends.cassandra.init.configuration.CassandraConsistenciesConfiguration.ConsistencyChoice.STRONG;
import static org.apache.james.backends.cassandra.init.configuration.CassandraConsistenciesConfiguration.ConsistencyChoice.WEAK;
import static org.apache.james.blob.api.BlobStore.StoragePolicy.SIZE_BASED;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.mail.Flags;
import javax.mail.Flags.Flag;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.backends.cassandra.init.configuration.CassandraConfiguration;
import org.apache.james.blob.api.BlobId;
import org.apache.james.blob.api.BlobStore;
import org.apache.james.mailbox.ApplicableFlagBuilder;
import org.apache.james.mailbox.FlagsBuilder;
import org.apache.james.mailbox.MessageManager.FlagsUpdateMode;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.ModSeq;
import org.apache.james.mailbox.cassandra.ids.CassandraId;
import org.apache.james.mailbox.cassandra.ids.CassandraMessageId;
import org.apache.james.mailbox.cassandra.mail.task.RecomputeMailboxCountersService;
import org.apache.james.mailbox.cassandra.mail.utils.FlagsUpdateStageResult;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.ComposedMessageIdWithMetaData;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxCounters;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.UpdatedFlags;
import org.apache.james.mailbox.store.FlagsUpdateCalculator;
import org.apache.james.mailbox.store.mail.MessageMapper;
import org.apache.james.mailbox.store.mail.ModSeqProvider;
import org.apache.james.mailbox.store.mail.UidProvider;
import org.apache.james.mailbox.store.mail.model.MailboxMessage;
import org.apache.james.mailbox.store.mail.model.impl.SimpleMailboxMessage;
import org.apache.james.task.Task;
import org.apache.james.util.ReactorUtils;
import org.apache.james.util.streams.Limit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

public class CassandraMessageMapper implements MessageMapper {
    public static final Logger LOGGER = LoggerFactory.getLogger(CassandraMessageMapper.class);
    private static final byte[] EMPTY_BYTE_ARRAY = {};

    private static final int MAX_RETRY = 5;
    private static final Duration MIN_RETRY_BACKOFF = Duration.ofMillis(10);
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofMillis(1000);

    private final ModSeqProvider modSeqProvider;
    private final UidProvider uidProvider;
    private final CassandraMessageDAO messageDAO;
    private final CassandraMessageDAOV3 messageDAOV3;
    private final CassandraMessageIdDAO messageIdDAO;
    private final CassandraMessageIdToImapUidDAO imapUidDAO;
    private final CassandraMailboxCounterDAO mailboxCounterDAO;
    private final CassandraMailboxRecentsDAO mailboxRecentDAO;
    private final CassandraApplicableFlagDAO applicableFlagDAO;
    private final CassandraIndexTableHandler indexTableHandler;
    private final CassandraFirstUnseenDAO firstUnseenDAO;
    private final AttachmentLoader attachmentLoader;
    private final CassandraDeletedMessageDAO deletedMessageDAO;
    private final BlobStore blobStore;
    private final CassandraConfiguration cassandraConfiguration;
    private final RecomputeMailboxCountersService recomputeMailboxCountersService;
    private final SecureRandom secureRandom;

    public CassandraMessageMapper(UidProvider uidProvider, ModSeqProvider modSeqProvider,
                                  CassandraAttachmentMapper attachmentMapper,
                                  CassandraMessageDAO messageDAO, CassandraMessageDAOV3 messageDAOV3, CassandraMessageIdDAO messageIdDAO,
                                  CassandraMessageIdToImapUidDAO imapUidDAO, CassandraMailboxCounterDAO mailboxCounterDAO,
                                  CassandraMailboxRecentsDAO mailboxRecentDAO, CassandraApplicableFlagDAO applicableFlagDAO,
                                  CassandraIndexTableHandler indexTableHandler, CassandraFirstUnseenDAO firstUnseenDAO,
                                  CassandraDeletedMessageDAO deletedMessageDAO, BlobStore blobStore, CassandraConfiguration cassandraConfiguration,
                                  RecomputeMailboxCountersService recomputeMailboxCountersService) {
        this.uidProvider = uidProvider;
        this.modSeqProvider = modSeqProvider;
        this.messageDAO = messageDAO;
        this.messageDAOV3 = messageDAOV3;
        this.messageIdDAO = messageIdDAO;
        this.imapUidDAO = imapUidDAO;
        this.mailboxCounterDAO = mailboxCounterDAO;
        this.mailboxRecentDAO = mailboxRecentDAO;
        this.indexTableHandler = indexTableHandler;
        this.firstUnseenDAO = firstUnseenDAO;
        this.attachmentLoader = new AttachmentLoader(attachmentMapper);
        this.applicableFlagDAO = applicableFlagDAO;
        this.deletedMessageDAO = deletedMessageDAO;
        this.blobStore = blobStore;
        this.cassandraConfiguration = cassandraConfiguration;
        this.recomputeMailboxCountersService = recomputeMailboxCountersService;
        this.secureRandom = new SecureRandom();
    }

    @Override
    public Flux<MessageUid> listAllMessageUids(Mailbox mailbox) {
        CassandraId cassandraId = (CassandraId) mailbox.getMailboxId();
        return messageIdDAO.listUids(cassandraId);
    }

    @Override
    public long countMessagesInMailbox(Mailbox mailbox) {
        return getMailboxCounters(mailbox).getCount();
    }

    @Override
    public MailboxCounters getMailboxCounters(Mailbox mailbox) {
        return getMailboxCountersReactive(mailbox).block();
    }

    @Override
    public Mono<MailboxCounters> getMailboxCountersReactive(Mailbox mailbox) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return readMailboxCounters(mailboxId)
            .flatMap(counters -> {
                if (!counters.isValid()) {
                    return fixCounters(mailbox)
                        .then(readMailboxCounters(mailboxId));
                }
                return Mono.just(counters);
            })
            .doOnNext(counters -> readRepair(mailbox, counters));
    }

    public Mono<MailboxCounters> readMailboxCounters(CassandraId mailboxId) {
        return mailboxCounterDAO.retrieveMailboxCounters(mailboxId)
            .defaultIfEmpty(MailboxCounters.empty(mailboxId));
    }

    private void readRepair(Mailbox mailbox, MailboxCounters counters) {
        if (shouldReadRepair(counters)) {
            fixCounters(mailbox)
                .subscribeOn(Schedulers.elastic())
                .subscribe();
        }
    }

    private Mono<Task.Result> fixCounters(Mailbox mailbox) {
        return recomputeMailboxCountersService.recomputeMailboxCounter(
                new RecomputeMailboxCountersService.Context(),
                mailbox,
                RecomputeMailboxCountersService.Options.trustMessageProjection());
    }

    private boolean shouldReadRepair(MailboxCounters counters) {
        boolean activated = cassandraConfiguration.getMailboxCountersReadRepairChanceMax() != 0 || cassandraConfiguration.getMailboxCountersReadRepairChanceOneHundred() != 0;
        double ponderedReadRepairChance = cassandraConfiguration.getMailboxCountersReadRepairChanceOneHundred() * (100.0 / counters.getUnseen());
        return activated &&
            secureRandom.nextFloat() < Math.min(
                cassandraConfiguration.getMailboxCountersReadRepairChanceMax(),
                ponderedReadRepairChance);
    }

    @Override
    public void delete(Mailbox mailbox, MailboxMessage message) {
        ComposedMessageIdWithMetaData metaData = message.getComposedMessageIdWithMetaData();

        deleteAndHandleIndexUpdates(metaData)
            .block();
    }

    private Mono<Void> deleteAndHandleIndexUpdates(ComposedMessageIdWithMetaData composedMessageIdWithMetaData) {
        ComposedMessageId composedMessageId = composedMessageIdWithMetaData.getComposedMessageId();
        CassandraId mailboxId = (CassandraId) composedMessageId.getMailboxId();

        return delete(composedMessageIdWithMetaData)
             .then(indexTableHandler.updateIndexOnDelete(composedMessageIdWithMetaData, mailboxId));
    }

    private Mono<Void> deleteAndHandleIndexUpdates(Collection<ComposedMessageIdWithMetaData> composedMessageIdWithMetaData) {
        if (composedMessageIdWithMetaData.isEmpty()) {
            return Mono.empty();
        }
        ComposedMessageId composedMessageId = composedMessageIdWithMetaData.iterator().next().getComposedMessageId();
        CassandraId mailboxId = (CassandraId) composedMessageId.getMailboxId();

        return Flux.fromIterable(composedMessageIdWithMetaData)
             .concatMap(this::delete)
             .then(indexTableHandler.updateIndexOnDeleteComposedId(mailboxId, composedMessageIdWithMetaData));
    }

    private Mono<Void> delete(ComposedMessageIdWithMetaData composedMessageIdWithMetaData) {
        ComposedMessageId composedMessageId = composedMessageIdWithMetaData.getComposedMessageId();
        CassandraMessageId messageId = (CassandraMessageId) composedMessageId.getMessageId();
        CassandraId mailboxId = (CassandraId) composedMessageId.getMailboxId();
        MessageUid uid = composedMessageId.getUid();

        return Flux.merge(
                imapUidDAO.delete(messageId, mailboxId),
                messageIdDAO.delete(mailboxId, uid))
                .then();
    }

    @Override
    public Iterator<MailboxMessage> findInMailbox(Mailbox mailbox, MessageRange messageRange, FetchType ftype, int max) {
        return findInMailboxReactive(mailbox, messageRange, ftype, max)
            .toIterable()
            .iterator();
    }

    @Override
    public Flux<ComposedMessageIdWithMetaData> listMessagesMetadata(Mailbox mailbox, MessageRange set) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return messageIdDAO.retrieveMessages(mailboxId, set, Limit.unlimited())
            .map(CassandraMessageMetadata::getComposedMessageId);
    }

    @Override
    public Flux<MailboxMessage> findInMailboxReactive(Mailbox mailbox, MessageRange messageRange, FetchType ftype, int limitAsInt) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        Limit limit = Limit.from(limitAsInt);
        return limit.applyOnFlux(messageIdDAO.retrieveMessages(mailboxId, messageRange, limit))
            .flatMap(metadata -> toMailboxMessage(metadata, ftype), cassandraConfiguration.getMessageReadChunkSize())
            .sort(Comparator.comparing(MailboxMessage::getUid));
    }

    private Mono<MailboxMessage> toMailboxMessage(CassandraMessageMetadata metadata, FetchType fetchType) {
        if (fetchType == FetchType.Metadata && metadata.isComplete()) {
            return Mono.just(metadata.asMailboxMessage(EMPTY_BYTE_ARRAY));
        }
        if (fetchType == FetchType.Headers && metadata.isComplete()) {
            return Mono.from(blobStore.readBytes(blobStore.getDefaultBucketName(), metadata.getHeaderContent().get(), SIZE_BASED))
                .map(metadata::asMailboxMessage);
        }
        return messageDAOV3.retrieveMessage(metadata.getComposedMessageId(), fetchType)
            .switchIfEmpty(Mono.defer(() -> messageDAO.retrieveMessage(metadata.getComposedMessageId(), fetchType)))
            .map(messageRepresentation -> Pair.of(metadata.getComposedMessageId(), messageRepresentation))
            .flatMap(messageRepresentation -> attachmentLoader.addAttachmentToMessage(messageRepresentation, fetchType));
    }

    @Override
    public List<MessageUid> findRecentMessageUidsInMailbox(Mailbox mailbox) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return mailboxRecentDAO.getRecentMessageUidsInMailbox(mailboxId)
            .collectList()
            .block();
    }

    @Override
    public MessageUid findFirstUnseenMessageUid(Mailbox mailbox) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return firstUnseenDAO.retrieveFirstUnread(mailboxId)
                .blockOptional()
                .orElse(null);
    }

    @Override
    public List<MessageUid> retrieveMessagesMarkedForDeletion(Mailbox mailbox, MessageRange messageRange) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        return deletedMessageDAO.retrieveDeletedMessage(mailboxId, messageRange)
            .collect(ImmutableList.toImmutableList())
            .block();
    }

    @Override
    public Map<MessageUid, MessageMetaData> deleteMessages(Mailbox mailbox, List<MessageUid> uids) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        return Flux.fromIterable(MessageRange.toRanges(uids))
            .concatMap(range -> messageIdDAO.retrieveMessages(mailboxId, range, Limit.unlimited()))
            .map(CassandraMessageMetadata::getComposedMessageId)
            .flatMap(this::expungeOne, cassandraConfiguration.getExpungeChunkSize())
            .collect(ImmutableMap.toImmutableMap(MailboxMessage::getUid, MailboxMessage::metaData))
            .flatMap(messageMap -> indexTableHandler.updateIndexOnDelete(mailboxId, messageMap.values())
                .thenReturn(messageMap))
            .subscribeOn(Schedulers.elastic())
            .block();
    }

    private Mono<SimpleMailboxMessage> expungeOne(ComposedMessageIdWithMetaData metaData) {
        return delete(metaData)
            .then(messageDAOV3.retrieveMessage(metaData, FetchType.Metadata)
                .switchIfEmpty(Mono.defer(() -> messageDAO.retrieveMessage(metaData, FetchType.Metadata))))
            .map(pair -> pair.toMailboxMessage(metaData, ImmutableList.of()));
    }

    @Override
    public MessageMetaData move(Mailbox destinationMailbox, MailboxMessage original) throws MailboxException {
        ComposedMessageIdWithMetaData composedMessageIdWithMetaData = original.getComposedMessageIdWithMetaData();

        MessageMetaData messageMetaData = copy(destinationMailbox, original);
        deleteAndHandleIndexUpdates(composedMessageIdWithMetaData).block();

        return messageMetaData;
    }

    @Override
    public List<MessageMetaData> move(Mailbox mailbox, List<MailboxMessage> original) throws MailboxException {
        List<ComposedMessageIdWithMetaData> beforeCopy = original.stream()
            .map(MailboxMessage::getComposedMessageIdWithMetaData)
            .collect(ImmutableList.toImmutableList());

        List<MessageMetaData> messageMetaData = copy(mailbox, original);
        deleteAndHandleIndexUpdates(beforeCopy).block();

        return messageMetaData;
    }

    @Override
    public ModSeq getHighestModSeq(Mailbox mailbox) throws MailboxException {
        return modSeqProvider.highestModSeq(mailbox);
    }

    @Override
    public MessageMetaData add(Mailbox mailbox, MailboxMessage message) throws MailboxException {
        return block(addReactive(mailbox, message));
    }

    @Override
    public Mono<MessageMetaData> addReactive(Mailbox mailbox, MailboxMessage message) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        return addUidAndModseq(message, mailboxId)
            .flatMap(Throwing.function((MailboxMessage messageWithUidAndModSeq) ->
                save(mailbox, messageWithUidAndModSeq)
                    .thenReturn(messageWithUidAndModSeq)))
            .map(MailboxMessage::metaData);
    }

    private Mono<MailboxMessage> addUidAndModseq(MailboxMessage message, CassandraId mailboxId) {
        Mono<MessageUid> messageUidMono = uidProvider
            .nextUidReactive(mailboxId)
            .switchIfEmpty(Mono.error(() -> new MailboxException("Can not find a UID to save " + message.getMessageId() + " in " + mailboxId)));

        Mono<ModSeq> nextModSeqMono = modSeqProvider.nextModSeqReactive(mailboxId)
            .switchIfEmpty(Mono.error(() -> new MailboxException("Can not find a MODSEQ to save " + message.getMessageId() + " in " + mailboxId)));

        return Mono.zip(messageUidMono, nextModSeqMono)
                .doOnNext(tuple -> {
                    message.setUid(tuple.getT1());
                    message.setModSeq(tuple.getT2());
                })
                .thenReturn(message);
    }

    private <T> T block(Mono<T> mono) throws MailboxException {
        try {
            return mono.block();
        } catch (RuntimeException e) {
            if (e.getCause() instanceof MailboxException) {
                throw (MailboxException) e.getCause();
            }
            throw e;
        }
    }

    @Override
    public Iterator<UpdatedFlags> updateFlags(Mailbox mailbox, FlagsUpdateCalculator flagUpdateCalculator, MessageRange range) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        Flux<ComposedMessageIdWithMetaData> toBeUpdated = messageIdDAO.retrieveMessages(mailboxId, range, Limit.unlimited())
            .map(CassandraMessageMetadata::getComposedMessageId);

        return updateFlags(flagUpdateCalculator, mailboxId, toBeUpdated).iterator();
    }

    private List<UpdatedFlags> updateFlags(FlagsUpdateCalculator flagUpdateCalculator, CassandraId mailboxId, Flux<ComposedMessageIdWithMetaData> toBeUpdated) {
        FlagsUpdateStageResult firstResult = runUpdateStage(mailboxId, toBeUpdated, flagUpdateCalculator).block();
        FlagsUpdateStageResult finalResult = handleUpdatesStagedRetry(mailboxId, flagUpdateCalculator, firstResult);
        if (finalResult.containsFailedResults()) {
            LOGGER.error("Can not update following UIDs {} for mailbox {}", finalResult.getFailed(), mailboxId.asUuid());
        }
        return finalResult.getSucceeded();
    }

    @Override
    public List<UpdatedFlags> resetRecent(Mailbox mailbox) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        Flux<ComposedMessageIdWithMetaData> toBeUpdated = mailboxRecentDAO.getRecentMessageUidsInMailbox(mailboxId)
            .collectList()
            .flatMapIterable(MessageRange::toRanges)
            .concatMap(range -> messageIdDAO.retrieveMessages(mailboxId, range, Limit.unlimited()))
            .map(CassandraMessageMetadata::getComposedMessageId)
            .filter(message -> message.getFlags().contains(Flag.RECENT));
        FlagsUpdateCalculator calculator = new FlagsUpdateCalculator(new Flags(Flag.RECENT), FlagsUpdateMode.REMOVE);

        return updateFlags(calculator, mailboxId, toBeUpdated);
    }

    private FlagsUpdateStageResult handleUpdatesStagedRetry(CassandraId mailboxId, FlagsUpdateCalculator flagUpdateCalculator, FlagsUpdateStageResult firstResult) {
        FlagsUpdateStageResult globalResult = firstResult;
        int retryCount = 0;
        while (retryCount < cassandraConfiguration.getFlagsUpdateMessageMaxRetry() && globalResult.containsFailedResults()) {
            retryCount++;
            FlagsUpdateStageResult stageResult = retryUpdatesStage(mailboxId, flagUpdateCalculator, globalResult.getFailed()).block();
            globalResult = globalResult.keepSucceded().merge(stageResult);
        }
        return globalResult;
    }

    private Mono<FlagsUpdateStageResult> retryUpdatesStage(CassandraId mailboxId, FlagsUpdateCalculator flagsUpdateCalculator, List<ComposedMessageId> failed) {
        if (!failed.isEmpty()) {
            Flux<ComposedMessageIdWithMetaData> toUpdate = Flux.fromIterable(failed)
                .flatMap(ids -> imapUidDAO.retrieve((CassandraMessageId) ids.getMessageId(), Optional.of((CassandraId) ids.getMailboxId()), chooseReadConsistencyUponWrites())
                        .map(CassandraMessageMetadata::getComposedMessageId),
                    DEFAULT_CONCURRENCY);
            return runUpdateStage(mailboxId, toUpdate, flagsUpdateCalculator);
        } else {
            return Mono.empty();
        }
    }

    private ConsistencyChoice chooseReadConsistencyUponWrites() {
        if (cassandraConfiguration.isMessageWriteStrongConsistency()) {
            return STRONG;
        }
        return WEAK;
    }

    private Mono<FlagsUpdateStageResult> runUpdateStage(CassandraId mailboxId, Flux<ComposedMessageIdWithMetaData> toBeUpdated, FlagsUpdateCalculator flagsUpdateCalculator) {
        return computeNewModSeq(mailboxId)
            .flatMapMany(newModSeq -> toBeUpdated
            .concatMap(metadata -> tryFlagsUpdate(flagsUpdateCalculator, newModSeq, metadata)))
            .reduce(FlagsUpdateStageResult.none(), FlagsUpdateStageResult::merge)
            .flatMap(result -> updateIndexesForUpdatesResult(mailboxId, result));
    }

    private Mono<ModSeq> computeNewModSeq(CassandraId mailboxId) {
        return modSeqProvider.nextModSeqReactive(mailboxId)
            .switchIfEmpty(ReactorUtils.executeAndEmpty(() -> new RuntimeException("ModSeq generation failed for mailbox " + mailboxId.asUuid())));
    }

    private Mono<FlagsUpdateStageResult> updateIndexesForUpdatesResult(CassandraId mailboxId, FlagsUpdateStageResult result) {
        return indexTableHandler.updateIndexOnFlagsUpdate(mailboxId, result.getSucceeded())
            .onErrorResume(e -> {
                LOGGER.error("Could not update flag indexes for mailboxId {}. This will lead to inconsistencies across Cassandra tables", mailboxId, e);
                return Mono.empty();
            })
            .thenReturn(result);
    }

    @Override
    public MessageMetaData copy(Mailbox mailbox, MailboxMessage original) throws MailboxException {
        original.setFlags(new FlagsBuilder().add(original.createFlags()).add(Flag.RECENT).build());
        return setInMailbox(mailbox, original);
    }

    public List<MessageMetaData> copy(Mailbox mailbox, List<MailboxMessage> originals) throws MailboxException {
        return setInMailbox(mailbox, originals.stream()
            .map(original -> {
                original.setFlags(new FlagsBuilder().add(original.createFlags()).add(Flag.RECENT).build());
                return original;
            })
            .collect(ImmutableList.toImmutableList()));
    }

    @Override
    public Optional<MessageUid> getLastUid(Mailbox mailbox) throws MailboxException {
        return uidProvider.lastUid(mailbox);
    }

    @Override
    public Flags getApplicableFlag(Mailbox mailbox) {
        return ApplicableFlagBuilder.builder()
            .add(applicableFlagDAO.retrieveApplicableFlag((CassandraId) mailbox.getMailboxId())
                .defaultIfEmpty(new Flags())
                .block())
            .build();
    }

    private MessageMetaData setInMailbox(Mailbox mailbox, MailboxMessage message) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return block(addUidAndModseq(message, mailboxId)
            .flatMap(messageWithUidAndModseq ->
                insertMetadata(messageWithUidAndModseq, mailboxId,
                    CassandraMessageMetadata.from(messageWithUidAndModseq)
                        .withMailboxId(mailboxId))
                .thenReturn(messageWithUidAndModseq))
            .map(MailboxMessage::metaData));
    }

    private List<MessageMetaData> setInMailbox(Mailbox mailbox, List<MailboxMessage> messages) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        Mono<List<MessageUid>> uids = uidProvider.nextUids(mailboxId, messages.size());
        Mono<ModSeq> nextModSeq = modSeqProvider.nextModSeqReactive(mailboxId);

        Mono<List<MailboxMessage>> messagesWithUidAndModSeq = nextModSeq.flatMap(modSeq -> uids.map(uidList -> Pair.of(uidList, modSeq)))
            .map(pair -> pair.getKey().stream()
                .map(uid -> Pair.of(uid, pair.getRight())))
            .map(uidsAndModSeq -> Streams.zip(uidsAndModSeq, messages.stream(),
                (uidAndModseq, aMessage) -> {
                    aMessage.setUid(uidAndModseq.getKey());
                    aMessage.setModSeq((uidAndModseq.getValue()));
                    return aMessage;
                }).collect(ImmutableList.toImmutableList()));

        return block(messagesWithUidAndModSeq
            .flatMap(list -> insertIds(list, mailboxId).thenReturn(list))
            .map(list -> list.stream()
                .map(MailboxMessage::metaData)
                .collect(ImmutableList.toImmutableList())));
    }

    private Mono<Void> save(Mailbox mailbox, MailboxMessage message) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return messageDAOV3.save(message)
            .flatMap(headerAndBodyBlobIds -> insertIds(message, mailboxId, headerAndBodyBlobIds.getT1()));
    }

    private Mono<Void> insertIds(MailboxMessage message, CassandraId mailboxId, BlobId headerBlobId) {
        CassandraMessageMetadata metadata = CassandraMessageMetadata.from(message, headerBlobId);

        return insertMetadata(message, mailboxId, metadata);
    }

    private Mono<Void> insertMetadata(MailboxMessage message, CassandraId mailboxId, CassandraMessageMetadata metadata) {
        return imapUidDAO.insert(metadata)
            .then(Flux.merge(
                messageIdDAO.insert(metadata)
                    .retryWhen(Retry.backoff(MAX_RETRY, MIN_RETRY_BACKOFF).maxBackoff(MAX_RETRY_BACKOFF)),
                indexTableHandler.updateIndexOnAdd(message, mailboxId))
            .then());
    }


    private CassandraMessageMetadata computeId(MailboxMessage message, CassandraId mailboxId) {
        return CassandraMessageMetadata.from(message)
            .withMailboxId(mailboxId);
    }

    private Mono<Void> insertIds(Collection<MailboxMessage> messages, CassandraId mailboxId) {
        int lowConcurrency = 4;
        return Flux.fromIterable(messages)
            .map(message -> computeId(message, mailboxId))
            .concatMap(id -> imapUidDAO.insert(id).thenReturn(id))
            .flatMap(id -> messageIdDAO.insert(id)
                .retryWhen(Retry.backoff(MAX_RETRY, MIN_RETRY_BACKOFF).maxBackoff(MAX_RETRY_BACKOFF)), lowConcurrency)
            .then(indexTableHandler.updateIndexOnAdd(messages, mailboxId));
    }

    private Mono<FlagsUpdateStageResult> tryFlagsUpdate(FlagsUpdateCalculator flagUpdateCalculator, ModSeq newModSeq, ComposedMessageIdWithMetaData oldMetaData) {
        Flags oldFlags = oldMetaData.getFlags();
        Flags newFlags = flagUpdateCalculator.buildNewFlags(oldFlags);

        if (identicalFlags(oldFlags, newFlags)) {
            return Mono.just(FlagsUpdateStageResult.success(UpdatedFlags.builder()
                .uid(oldMetaData.getComposedMessageId().getUid())
                .messageId(oldMetaData.getComposedMessageId().getMessageId())
                .modSeq(oldMetaData.getModSeq())
                .oldFlags(oldFlags)
                .newFlags(newFlags)
                .build()));
        }

        return updateFlags(oldMetaData, newFlags, newModSeq)
            .map(success -> {
                if (success) {
                    return FlagsUpdateStageResult.success(UpdatedFlags.builder()
                        .uid(oldMetaData.getComposedMessageId().getUid())
                        .messageId(oldMetaData.getComposedMessageId().getMessageId())
                        .modSeq(newModSeq)
                        .oldFlags(oldFlags)
                        .newFlags(newFlags)
                        .build());
                } else {
                    return FlagsUpdateStageResult.fail(oldMetaData.getComposedMessageId());
                }
            });
    }

    private boolean identicalFlags(Flags oldFlags, Flags newFlags) {
        return oldFlags.equals(newFlags);
    }

    private Mono<Boolean> updateFlags(ComposedMessageIdWithMetaData oldMetadata, Flags newFlags, ModSeq newModSeq) {
        ComposedMessageIdWithMetaData newMetadata = ComposedMessageIdWithMetaData.builder()
            .composedMessageId(oldMetadata.getComposedMessageId())
            .modSeq(newModSeq)
            .flags(newFlags)
            .threadId(oldMetadata.getThreadId())
            .build();

        ComposedMessageId composedMessageId = newMetadata.getComposedMessageId();
        ModSeq previousModseq = oldMetadata.getModSeq();
        UpdatedFlags updatedFlags = UpdatedFlags.builder()
            .messageId(composedMessageId.getMessageId())
            .modSeq(newMetadata.getModSeq())
            .oldFlags(oldMetadata.getFlags())
            .newFlags(newMetadata.getFlags())
            .uid(composedMessageId.getUid())
            .build();

        return imapUidDAO.updateMetadata(composedMessageId, updatedFlags, previousModseq)
            .flatMap(success -> {
                if (success) {
                    return messageIdDAO.updateMetadata(composedMessageId, updatedFlags).thenReturn(true);
                } else {
                    return Mono.just(false);
                }
            });
    }
}
