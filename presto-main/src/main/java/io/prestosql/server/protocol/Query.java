/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.server.protocol;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.log.Logger;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.client.ClientCapabilities;
import io.prestosql.client.ClientTypeSignature;
import io.prestosql.client.ClientTypeSignatureParameter;
import io.prestosql.client.Column;
import io.prestosql.client.FailureInfo;
import io.prestosql.client.NamedClientTypeSignature;
import io.prestosql.client.QueryError;
import io.prestosql.client.QueryResults;
import io.prestosql.client.RowFieldName;
import io.prestosql.client.StageStats;
import io.prestosql.client.StatementStats;
import io.prestosql.client.Warning;
import io.prestosql.execution.ExecutionFailureInfo;
import io.prestosql.execution.QueryExecution;
import io.prestosql.execution.QueryInfo;
import io.prestosql.execution.QueryManager;
import io.prestosql.execution.QueryState;
import io.prestosql.execution.QueryStats;
import io.prestosql.execution.StageId;
import io.prestosql.execution.StageInfo;
import io.prestosql.execution.TaskInfo;
import io.prestosql.execution.buffer.PagesSerde;
import io.prestosql.execution.buffer.PagesSerdeFactory;
import io.prestosql.execution.buffer.SerializedPage;
import io.prestosql.operator.ExchangeClient;
import io.prestosql.spi.ErrorCode;
import io.prestosql.spi.Page;
import io.prestosql.spi.PrestoWarning;
import io.prestosql.spi.QueryId;
import io.prestosql.spi.WarningCode;
import io.prestosql.spi.block.BlockEncodingSerde;
import io.prestosql.spi.security.SelectedRole;
import io.prestosql.spi.type.BooleanType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.spi.type.TypeSignatureParameter;
import io.prestosql.sql.ExpressionFormatter;
import io.prestosql.sql.analyzer.TypeSignatureTranslator;
import io.prestosql.sql.tree.DataType;
import io.prestosql.sql.tree.DateTimeDataType;
import io.prestosql.sql.tree.GenericDataType;
import io.prestosql.sql.tree.IntervalDayTimeDataType;
import io.prestosql.sql.tree.NumericParameter;
import io.prestosql.sql.tree.RowDataType;
import io.prestosql.sql.tree.TypeParameter;
import io.prestosql.transaction.TransactionId;
import io.prestosql.util.Failures;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.concurrent.MoreFutures.addTimeout;
import static io.prestosql.SystemSessionProperties.isExchangeCompressionEnabled;
import static io.prestosql.execution.QueryState.FAILED;
import static io.prestosql.server.protocol.QueryResultRows.queryResultRowsBuilder;
import static io.prestosql.server.protocol.Slug.Context.EXECUTING_QUERY;
import static io.prestosql.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.prestosql.spi.StandardErrorCode.SERIALIZATION_ERROR;
import static io.prestosql.spi.type.StandardTypes.ROW;
import static io.prestosql.spi.type.StandardTypes.TIMESTAMP;
import static io.prestosql.spi.type.StandardTypes.TIMESTAMP_WITH_TIME_ZONE;
import static io.prestosql.util.Failures.toFailure;
import static io.prestosql.util.MoreLists.mappedCopy;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

@ThreadSafe
class Query
{
    private static final Logger log = Logger.get(Query.class);

    private final QueryManager queryManager;
    private final QueryId queryId;
    private final Session session;
    private final Slug slug;

    @GuardedBy("this")
    private final ExchangeClient exchangeClient;

    private final Executor resultsProcessorExecutor;
    private final ScheduledExecutorService timeoutExecutor;

    private final PagesSerde serde;
    private final boolean supportsParametricDateTime;

    @GuardedBy("this")
    private OptionalLong nextToken = OptionalLong.of(0);

    @GuardedBy("this")
    private QueryResults lastResult;

    @GuardedBy("this")
    private long lastToken = -1;

    @GuardedBy("this")
    private List<Column> columns;

    @GuardedBy("this")
    private List<Type> types;

    @GuardedBy("this")
    private Optional<String> setCatalog = Optional.empty();

    @GuardedBy("this")
    private Optional<String> setSchema = Optional.empty();

    @GuardedBy("this")
    private Optional<String> setPath = Optional.empty();

    @GuardedBy("this")
    private Map<String, String> setSessionProperties = ImmutableMap.of();

    @GuardedBy("this")
    private Set<String> resetSessionProperties = ImmutableSet.of();

    @GuardedBy("this")
    private Map<String, SelectedRole> setRoles = ImmutableMap.of();

    @GuardedBy("this")
    private Map<String, String> addedPreparedStatements = ImmutableMap.of();

    @GuardedBy("this")
    private Set<String> deallocatedPreparedStatements = ImmutableSet.of();

    @GuardedBy("this")
    private Optional<TransactionId> startedTransactionId = Optional.empty();

    @GuardedBy("this")
    private boolean clearTransactionId;

    @GuardedBy("this")
    private Optional<Throwable> typeSerializationException = Optional.empty();

    @GuardedBy("this")
    private Long updateCount;

    public static Query create(
            Session session,
            Slug slug,
            QueryManager queryManager,
            ExchangeClient exchangeClient,
            Executor dataProcessorExecutor,
            ScheduledExecutorService timeoutExecutor,
            BlockEncodingSerde blockEncodingSerde)
    {
        Query result = new Query(session, slug, queryManager, exchangeClient, dataProcessorExecutor, timeoutExecutor, blockEncodingSerde);

        result.queryManager.addOutputInfoListener(result.getQueryId(), result::setQueryOutputInfo);

        result.queryManager.addStateChangeListener(result.getQueryId(), state -> {
            if (state.isDone()) {
                QueryInfo queryInfo = queryManager.getFullQueryInfo(result.getQueryId());
                result.closeExchangeClientIfNecessary(queryInfo);
            }
        });

        return result;
    }

    private Query(
            Session session,
            Slug slug,
            QueryManager queryManager,
            ExchangeClient exchangeClient,
            Executor resultsProcessorExecutor,
            ScheduledExecutorService timeoutExecutor,
            BlockEncodingSerde blockEncodingSerde)
    {
        requireNonNull(session, "session is null");
        requireNonNull(slug, "slug is null");
        requireNonNull(queryManager, "queryManager is null");
        requireNonNull(exchangeClient, "exchangeClient is null");
        requireNonNull(resultsProcessorExecutor, "resultsProcessorExecutor is null");
        requireNonNull(timeoutExecutor, "timeoutExecutor is null");
        requireNonNull(blockEncodingSerde, "serde is null");

        this.queryManager = queryManager;

        this.queryId = session.getQueryId();
        this.session = session;
        this.slug = slug;
        this.exchangeClient = exchangeClient;
        this.resultsProcessorExecutor = resultsProcessorExecutor;
        this.timeoutExecutor = timeoutExecutor;
        this.supportsParametricDateTime = session.getClientCapabilities().contains(ClientCapabilities.PARAMETRIC_DATETIME.toString());
        serde = new PagesSerdeFactory(blockEncodingSerde, isExchangeCompressionEnabled(session)).createPagesSerde();
    }

    public void cancel()
    {
        queryManager.cancelQuery(queryId);
        dispose();
    }

    public void partialCancel(int id)
    {
        StageId stageId = new StageId(queryId, id);
        queryManager.cancelStage(stageId);
    }

    public void fail(Throwable throwable)
    {
        queryManager.failQuery(queryId, throwable);
    }

    public synchronized void dispose()
    {
        exchangeClient.close();
    }

    public QueryId getQueryId()
    {
        return queryId;
    }

    public boolean isSlugValid(String slug, long token)
    {
        return this.slug.isValid(EXECUTING_QUERY, slug, token);
    }

    public QueryInfo getQueryInfo()
    {
        return queryManager.getFullQueryInfo(queryId);
    }

    public synchronized Optional<String> getSetCatalog()
    {
        return setCatalog;
    }

    public synchronized Optional<String> getSetSchema()
    {
        return setSchema;
    }

    public synchronized Optional<String> getSetPath()
    {
        return setPath;
    }

    public synchronized Map<String, String> getSetSessionProperties()
    {
        return setSessionProperties;
    }

    public synchronized Set<String> getResetSessionProperties()
    {
        return resetSessionProperties;
    }

    public synchronized Map<String, SelectedRole> getSetRoles()
    {
        return setRoles;
    }

    public synchronized Map<String, String> getAddedPreparedStatements()
    {
        return addedPreparedStatements;
    }

    public synchronized Set<String> getDeallocatedPreparedStatements()
    {
        return deallocatedPreparedStatements;
    }

    public synchronized Optional<TransactionId> getStartedTransactionId()
    {
        return startedTransactionId;
    }

    public synchronized boolean isClearTransactionId()
    {
        return clearTransactionId;
    }

    public synchronized ListenableFuture<QueryResults> waitForResults(long token, UriInfo uriInfo, Duration wait, DataSize targetResultSize)
    {
        // before waiting, check if this request has already been processed and cached
        Optional<QueryResults> cachedResult = getCachedResult(token);
        if (cachedResult.isPresent()) {
            return immediateFuture(cachedResult.get());
        }

        // wait for a results data or query to finish, up to the wait timeout
        ListenableFuture<?> futureStateChange = addTimeout(
                getFutureStateChange(),
                () -> null,
                wait,
                timeoutExecutor);

        // when state changes, fetch the next result
        return Futures.transform(futureStateChange, ignored -> getNextResult(token, uriInfo, targetResultSize), resultsProcessorExecutor);
    }

    private synchronized ListenableFuture<?> getFutureStateChange()
    {
        // if the exchange client is open, wait for data
        if (!exchangeClient.isClosed()) {
            return exchangeClient.isBlocked();
        }

        // otherwise, wait for the query to finish
        queryManager.recordHeartbeat(queryId);
        try {
            return queryDoneFuture(queryManager.getQueryState(queryId));
        }
        catch (NoSuchElementException e) {
            return immediateFuture(null);
        }
    }

    private synchronized Optional<QueryResults> getCachedResult(long token)
    {
        // is this the first request?
        if (lastResult == null) {
            return Optional.empty();
        }

        // is the a repeated request for the last results?
        if (token == lastToken) {
            // tell query manager we are still interested in the query
            queryManager.recordHeartbeat(queryId);
            return Optional.of(lastResult);
        }

        // if this is a result before the lastResult, the data is gone
        if (token < lastToken) {
            throw new WebApplicationException(Response.Status.GONE);
        }

        // if this is a request for a result after the end of the stream, return not found
        if (nextToken.isEmpty()) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        // if this is not a request for the next results, return not found
        if (token != nextToken.getAsLong()) {
            // unknown token
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        return Optional.empty();
    }

    private synchronized QueryResults getNextResult(long token, UriInfo uriInfo, DataSize targetResultSize)
    {
        // check if the result for the token have already been created
        Optional<QueryResults> cachedResult = getCachedResult(token);
        if (cachedResult.isPresent()) {
            return cachedResult.get();
        }

        verify(nextToken.isPresent(), "Cannot generate next result when next token is not present");
        verify(token == nextToken.getAsLong(), "Expected token to equal next token");
        URI queryHtmlUri = uriInfo.getRequestUriBuilder()
                .replacePath("ui/query.html")
                .replaceQuery(queryId.toString())
                .build();

        // get the query info before returning
        // force update if query manager is closed
        QueryInfo queryInfo = queryManager.getFullQueryInfo(queryId);
        queryManager.recordHeartbeat(queryId);

        // fetch result data from exchange
        QueryResultRows resultRows = removePagesFromExchange(queryInfo, targetResultSize.toBytes());

        if ((queryInfo.getUpdateType() != null) && (updateCount == null)) {
            // grab the update count for non-queries
            Optional<Long> updatedRowsCount = resultRows.getUpdateCount();
            updateCount = updatedRowsCount.orElse(null);
        }

        closeExchangeClientIfNecessary(queryInfo);

        // advance next token
        // only return a next if
        // (1) the query is not done AND the query state is not FAILED
        //   OR
        // (2)there is more data to send (due to buffering)
        if ((!queryInfo.isFinalQueryInfo() && queryInfo.getState() != FAILED) || !exchangeClient.isClosed()) {
            nextToken = OptionalLong.of(token + 1);
        }
        else {
            nextToken = OptionalLong.empty();
        }

        URI nextResultsUri = null;
        URI partialCancelUri = null;
        if (nextToken.isPresent()) {
            nextResultsUri = createNextResultsUri(uriInfo, nextToken.getAsLong());
            partialCancelUri = findCancelableLeafStage(queryInfo)
                    .map(stage -> this.createPartialCancelUri(stage, uriInfo, nextToken.getAsLong()))
                    .orElse(null);
        }

        // update catalog, schema, and path
        setCatalog = queryInfo.getSetCatalog();
        setSchema = queryInfo.getSetSchema();
        setPath = queryInfo.getSetPath();

        // update setSessionProperties
        setSessionProperties = queryInfo.getSetSessionProperties();
        resetSessionProperties = queryInfo.getResetSessionProperties();

        // update setRoles
        setRoles = queryInfo.getSetRoles();

        // update preparedStatements
        addedPreparedStatements = queryInfo.getAddedPreparedStatements();
        deallocatedPreparedStatements = queryInfo.getDeallocatedPreparedStatements();

        // update startedTransactionId
        startedTransactionId = queryInfo.getStartedTransactionId();
        clearTransactionId = queryInfo.isClearTransactionId();

        // first time through, self is null
        QueryResults queryResults = new QueryResults(
                queryId.toString(),
                queryHtmlUri,
                partialCancelUri,
                nextResultsUri,
                resultRows.getColumns().orElse(null),
                resultRows.isEmpty() ? null : resultRows, // client excepts null that indicates "no data"
                toStatementStats(queryInfo),
                toQueryError(queryInfo, typeSerializationException),
                mappedCopy(queryInfo.getWarnings(), Query::toClientWarning),
                queryInfo.getUpdateType(),
                updateCount);

        // cache the new result
        lastToken = token;
        lastResult = queryResults;

        return queryResults;
    }

    private synchronized QueryResultRows removePagesFromExchange(QueryInfo queryInfo, long targetResultBytes)
    {
        // For queries with no output, return a fake boolean result for clients that require it.
        if ((queryInfo.getState() == QueryState.FINISHED) && queryInfo.getOutputStage().isEmpty()) {
            return queryResultRowsBuilder(session)
                    .withSingleBooleanValue(createColumn("result", BooleanType.BOOLEAN), true)
                    .build();
        }

        // Remove as many pages as possible from the exchange until just greater than DESIRED_RESULT_BYTES
        // NOTE: it is critical that query results are created for the pages removed from the exchange
        // client while holding the lock because the query may transition to the finished state when the
        // last page is removed.  If another thread observes this state before the response is cached
        // the pages will be lost.
        QueryResultRows.Builder resultBuilder = queryResultRowsBuilder(session)
                // Intercept serialization exceptions and fail query if it's still possible.
                // Put serialization exception aside to return failed query result.
                .withExceptionConsumer(this::handleSerializationException)
                .withColumnsAndTypes(columns, types);

        try {
            long bytes = 0;
            while (bytes < targetResultBytes) {
                SerializedPage serializedPage = exchangeClient.pollPage();
                if (serializedPage == null) {
                    break;
                }

                Page page = serde.deserialize(serializedPage);
                bytes += page.getLogicalSizeInBytes();
                resultBuilder.addPage(page);
            }
        }
        catch (Throwable cause) {
            queryManager.failQuery(queryId, cause);
        }

        return resultBuilder.build();
    }

    private synchronized void closeExchangeClientIfNecessary(QueryInfo queryInfo)
    {
        // Close the exchange client if the query has failed, or if the query
        // is done and it does not have an output stage. The latter happens
        // for data definition executions, as those do not have output.
        if ((queryInfo.getState() == FAILED) ||
                (queryInfo.getState().isDone() && queryInfo.getOutputStage().isEmpty())) {
            exchangeClient.close();
        }
    }

    private void handleSerializationException(Throwable exception)
    {
        // failQuery can throw exception if query has already finished.
        try {
            queryManager.failQuery(queryId, exception);
        }
        catch (RuntimeException e) {
            log.debug("Could not fail query", e);
        }

        if (typeSerializationException.isEmpty()) {
            typeSerializationException = Optional.of(exception);
        }
    }

    private synchronized void setQueryOutputInfo(QueryExecution.QueryOutputInfo outputInfo)
    {
        // if first callback, set column names
        if (columns == null) {
            List<String> columnNames = outputInfo.getColumnNames();
            List<Type> columnTypes = outputInfo.getColumnTypes();
            checkArgument(columnNames.size() == columnTypes.size(), "Column names and types size mismatch");

            ImmutableList.Builder<Column> list = ImmutableList.builder();
            for (int i = 0; i < columnNames.size(); i++) {
                list.add(createColumn(columnNames.get(i), columnTypes.get(i)));
            }
            columns = list.build();
            types = outputInfo.getColumnTypes();
        }

        for (URI outputLocation : outputInfo.getBufferLocations()) {
            exchangeClient.addLocation(outputLocation);
        }
        if (outputInfo.isNoMoreBufferLocations()) {
            exchangeClient.noMoreLocations();
        }
    }

    private ListenableFuture<?> queryDoneFuture(QueryState currentState)
    {
        if (currentState.isDone()) {
            return immediateFuture(null);
        }
        return Futures.transformAsync(queryManager.getStateChange(queryId, currentState), this::queryDoneFuture, directExecutor());
    }

    private synchronized URI createNextResultsUri(UriInfo uriInfo, long nextToken)
    {
        return uriInfo.getBaseUriBuilder()
                .replacePath("/v1/statement/executing")
                .path(queryId.toString())
                .path(slug.makeSlug(EXECUTING_QUERY, nextToken))
                .path(String.valueOf(nextToken))
                .replaceQuery("")
                .build();
    }

    private URI createPartialCancelUri(int stage, UriInfo uriInfo, long nextToken)
    {
        return uriInfo.getBaseUriBuilder()
                .replacePath("/v1/statement/executing/partialCancel")
                .path(queryId.toString())
                .path(String.valueOf(stage))
                .path(slug.makeSlug(EXECUTING_QUERY, nextToken))
                .path(String.valueOf(nextToken))
                .replaceQuery("")
                .build();
    }

    private Column createColumn(String name, Type type)
    {
        String formatted = formatType(TypeSignatureTranslator.toSqlType(type));

        return new Column(name, formatted, toClientTypeSignature(type.getTypeSignature()));
    }

    private String formatType(DataType type)
    {
        if (type instanceof DateTimeDataType) {
            DateTimeDataType dataTimeType = (DateTimeDataType) type;
            if (dataTimeType.getType() == DateTimeDataType.Type.TIMESTAMP && !supportsParametricDateTime) {
                if (dataTimeType.isWithTimeZone()) {
                    return TIMESTAMP_WITH_TIME_ZONE;
                }
                else {
                    return TIMESTAMP;
                }
            }

            return ExpressionFormatter.formatExpression(type);
        }
        else if (type instanceof RowDataType) {
            RowDataType rowDataType = (RowDataType) type;
            return rowDataType.getFields().stream()
                    .map(field -> field.getName().map(name -> name + " ").orElse("") + formatType(field.getType()))
                    .collect(Collectors.joining(", ", ROW + "(", ")"));
        }
        else if (type instanceof GenericDataType) {
            GenericDataType dataType = (GenericDataType) type;
            if (dataType.getArguments().isEmpty()) {
                return dataType.getName().getValue();
            }

            return dataType.getArguments().stream()
                    .map(parameter -> {
                        if (parameter instanceof NumericParameter) {
                            return ((NumericParameter) parameter).getValue();
                        }
                        else if (parameter instanceof TypeParameter) {
                            return formatType(((TypeParameter) parameter).getValue());
                        }
                        throw new IllegalArgumentException("Unsupported parameter type: " + parameter.getClass().getName());
                    })
                    .collect(Collectors.joining(", ", dataType.getName().getValue() + "(", ")"));
        }
        else if (type instanceof IntervalDayTimeDataType) {
            return ExpressionFormatter.formatExpression(type);
        }

        throw new IllegalArgumentException("Unsupported data type: " + type.getClass().getName());
    }

    private ClientTypeSignature toClientTypeSignature(TypeSignature signature)
    {
        if (!supportsParametricDateTime) {
            if (signature.getBase().equalsIgnoreCase(TIMESTAMP)) {
                return new ClientTypeSignature(TIMESTAMP);
            }
            else if (signature.getBase().equalsIgnoreCase(TIMESTAMP_WITH_TIME_ZONE)) {
                return new ClientTypeSignature(TIMESTAMP_WITH_TIME_ZONE);
            }
        }

        return new ClientTypeSignature(signature.getBase(), signature.getParameters().stream()
                .map(this::toClientTypeSignatureParameter)
                .collect(toImmutableList()));
    }

    private ClientTypeSignatureParameter toClientTypeSignatureParameter(TypeSignatureParameter parameter)
    {
        switch (parameter.getKind()) {
            case TYPE:
                return ClientTypeSignatureParameter.ofType(toClientTypeSignature(parameter.getTypeSignature()));
            case NAMED_TYPE:
                return ClientTypeSignatureParameter.ofNamedType(new NamedClientTypeSignature(
                        parameter.getNamedTypeSignature().getFieldName().map(value ->
                                new RowFieldName(value.getName())),
                        toClientTypeSignature(parameter.getNamedTypeSignature().getTypeSignature())));
            case LONG:
                return ClientTypeSignatureParameter.ofLong(parameter.getLongLiteral());
        }
        throw new IllegalArgumentException("Unsupported kind: " + parameter.getKind());
    }

    private static StatementStats toStatementStats(QueryInfo queryInfo)
    {
        QueryStats queryStats = queryInfo.getQueryStats();
        StageInfo outputStage = queryInfo.getOutputStage().orElse(null);

        return StatementStats.builder()
                .setState(queryInfo.getState().toString())
                .setQueued(queryInfo.getState() == QueryState.QUEUED)
                .setScheduled(queryInfo.isScheduled())
                .setNodes(globalUniqueNodes(outputStage).size())
                .setTotalSplits(queryStats.getTotalDrivers())
                .setQueuedSplits(queryStats.getQueuedDrivers())
                .setRunningSplits(queryStats.getRunningDrivers() + queryStats.getBlockedDrivers())
                .setCompletedSplits(queryStats.getCompletedDrivers())
                .setCpuTimeMillis(queryStats.getTotalCpuTime().toMillis())
                .setWallTimeMillis(queryStats.getTotalScheduledTime().toMillis())
                .setQueuedTimeMillis(queryStats.getQueuedTime().toMillis())
                .setElapsedTimeMillis(queryStats.getElapsedTime().toMillis())
                .setProcessedRows(queryStats.getRawInputPositions())
                .setProcessedBytes(queryStats.getRawInputDataSize().toBytes())
                .setPeakMemoryBytes(queryStats.getPeakUserMemoryReservation().toBytes())
                .setSpilledBytes(queryStats.getSpilledDataSize().toBytes())
                .setRootStage(toStageStats(outputStage))
                .build();
    }

    private static StageStats toStageStats(StageInfo stageInfo)
    {
        if (stageInfo == null) {
            return null;
        }

        io.prestosql.execution.StageStats stageStats = stageInfo.getStageStats();

        ImmutableList.Builder<StageStats> subStages = ImmutableList.builder();
        for (StageInfo subStage : stageInfo.getSubStages()) {
            subStages.add(toStageStats(subStage));
        }

        Set<String> uniqueNodes = new HashSet<>();
        for (TaskInfo task : stageInfo.getTasks()) {
            // todo add nodeId to TaskInfo
            URI uri = task.getTaskStatus().getSelf();
            uniqueNodes.add(uri.getHost() + ":" + uri.getPort());
        }

        return StageStats.builder()
                .setStageId(String.valueOf(stageInfo.getStageId().getId()))
                .setState(stageInfo.getState().toString())
                .setDone(stageInfo.getState().isDone())
                .setNodes(uniqueNodes.size())
                .setTotalSplits(stageStats.getTotalDrivers())
                .setQueuedSplits(stageStats.getQueuedDrivers())
                .setRunningSplits(stageStats.getRunningDrivers() + stageStats.getBlockedDrivers())
                .setCompletedSplits(stageStats.getCompletedDrivers())
                .setCpuTimeMillis(stageStats.getTotalCpuTime().toMillis())
                .setWallTimeMillis(stageStats.getTotalScheduledTime().toMillis())
                .setProcessedRows(stageStats.getRawInputPositions())
                .setProcessedBytes(stageStats.getRawInputDataSize().toBytes())
                .setSubStages(subStages.build())
                .build();
    }

    private static Set<String> globalUniqueNodes(StageInfo stageInfo)
    {
        if (stageInfo == null) {
            return ImmutableSet.of();
        }
        ImmutableSet.Builder<String> nodes = ImmutableSet.builder();
        for (TaskInfo task : stageInfo.getTasks()) {
            // todo add nodeId to TaskInfo
            URI uri = task.getTaskStatus().getSelf();
            nodes.add(uri.getHost() + ":" + uri.getPort());
        }

        for (StageInfo subStage : stageInfo.getSubStages()) {
            nodes.addAll(globalUniqueNodes(subStage));
        }
        return nodes.build();
    }

    private static Optional<Integer> findCancelableLeafStage(QueryInfo queryInfo)
    {
        // if query is running, find the leaf-most running stage
        return queryInfo.getOutputStage().flatMap(Query::findCancelableLeafStage);
    }

    private static Optional<Integer> findCancelableLeafStage(StageInfo stage)
    {
        // if this stage is already done, we can't cancel it
        if (stage.getState().isDone()) {
            return Optional.empty();
        }

        // attempt to find a cancelable sub stage
        // check in reverse order since build side of a join will be later in the list
        for (StageInfo subStage : Lists.reverse(stage.getSubStages())) {
            Optional<Integer> leafStage = findCancelableLeafStage(subStage);
            if (leafStage.isPresent()) {
                return leafStage;
            }
        }

        // no matching sub stage, so return this stage
        return Optional.of(stage.getStageId().getId());
    }

    private static QueryError toQueryError(QueryInfo queryInfo, Optional<Throwable> exception)
    {
        QueryState state = queryInfo.getState();
        if (state != FAILED && exception.isEmpty()) {
            return null;
        }

        ExecutionFailureInfo executionFailure;
        if (queryInfo.getFailureInfo() != null) {
            executionFailure = queryInfo.getFailureInfo();
        }
        else if (exception.isPresent()) {
            executionFailure = Failures.toFailure(exception.get());
        }
        else {
            log.warn("Query %s in state %s has no failure info", queryInfo.getQueryId(), state);
            executionFailure = toFailure(new RuntimeException(format("Query is %s (reason unknown)", state)));
        }
        FailureInfo failure = executionFailure.toFailureInfo();

        ErrorCode errorCode;
        if (queryInfo.getErrorCode() != null) {
            errorCode = queryInfo.getErrorCode();
        }
        else if (exception.isPresent()) {
            errorCode = SERIALIZATION_ERROR.toErrorCode();
        }
        else {
            errorCode = GENERIC_INTERNAL_ERROR.toErrorCode();
            log.warn("Failed query %s has no error code", queryInfo.getQueryId());
        }
        return new QueryError(
                firstNonNull(failure.getMessage(), "Internal error"),
                null,
                errorCode.getCode(),
                errorCode.getName(),
                errorCode.getType().toString(),
                failure.getErrorLocation(),
                failure);
    }

    private static Warning toClientWarning(PrestoWarning warning)
    {
        WarningCode code = warning.getWarningCode();
        return new Warning(new Warning.Code(code.getCode(), code.getName()), warning.getMessage());
    }
}
