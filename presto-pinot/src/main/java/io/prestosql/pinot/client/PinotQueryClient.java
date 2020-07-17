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
package io.prestosql.pinot.client;

import com.yammer.metrics.core.MetricsRegistry;
import io.prestosql.pinot.PinotConfig;
import io.prestosql.pinot.PinotException;
import org.apache.helix.model.InstanceConfig;
import org.apache.pinot.common.config.TableNameBuilder;
import org.apache.pinot.common.metrics.BrokerMetrics;
import org.apache.pinot.common.request.BrokerRequest;
import org.apache.pinot.common.utils.CommonConstants;
import org.apache.pinot.common.utils.DataTable;
import org.apache.pinot.core.transport.AsyncQueryResponse;
import org.apache.pinot.core.transport.QueryRouter;
import org.apache.pinot.core.transport.ServerInstance;
import org.apache.pinot.core.transport.ServerResponse;
import org.apache.pinot.core.transport.ServerRoutingInstance;
import org.apache.pinot.sql.parsers.CalciteSqlCompiler;
import org.apache.pinot.sql.parsers.SqlCompilationException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static io.prestosql.pinot.PinotErrorCode.PINOT_EXCEPTION;
import static io.prestosql.pinot.PinotErrorCode.PINOT_INVALID_PQL_GENERATED;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class PinotQueryClient
{
    private static final CalciteSqlCompiler REQUEST_COMPILER = new CalciteSqlCompiler();
    private static final String PRESTO_HOST_PREFIX = "presto-pinot-master";
    private static final String SERVER_INSTANCE_PREFIX = "Server";
    private static final boolean DEFAULT_EMIT_TABLE_LEVEL_METRICS = true;

    private final String prestoHostId;
    private final BrokerMetrics brokerMetrics;
    private final QueryRouter queryRouter;
    private final AtomicLong requestIdGenerator = new AtomicLong();

    public PinotQueryClient(PinotConfig config)
    {
        requireNonNull(config, "config is null");
        prestoHostId = getDefaultPrestoId();
        MetricsRegistry registry = new MetricsRegistry();
        this.brokerMetrics = new BrokerMetrics(registry, DEFAULT_EMIT_TABLE_LEVEL_METRICS);
        brokerMetrics.initializeGlobalMeters();
        queryRouter = new QueryRouter(prestoHostId, brokerMetrics);
    }

    private static String getDefaultPrestoId()
    {
        String defaultBrokerId;
        try {
            defaultBrokerId = PRESTO_HOST_PREFIX + InetAddress.getLocalHost().getHostName();
        }
        catch (UnknownHostException e) {
            defaultBrokerId = PRESTO_HOST_PREFIX;
        }
        return defaultBrokerId;
    }

    public Map<ServerInstance, DataTable> queryPinotServerForDataTable(String query, String serverHost, List<String> segments, long connectionTimeoutInMillis, int pinotRetryCount)
    {
        // TODO: separate into offline and realtime methods
        BrokerRequest brokerRequest;
        try {
            brokerRequest = REQUEST_COMPILER.compileToBrokerRequest(query);
        }
        catch (SqlCompilationException e) {
            throw new PinotException(PINOT_INVALID_PQL_GENERATED, Optional.of(query), format("Parsing error with on %s, Error = %s", serverHost, e.getMessage()), e);
        }
        ServerInstance serverInstance = new ServerInstance(InstanceConfig.toInstanceConfig(serverHost));
        Map<ServerInstance, List<String>> routingTable = new HashMap<>();
        routingTable.put(serverInstance, new ArrayList<>(segments));
        String tableName = brokerRequest.getQuerySource().getTableName();
        String rawTableName = TableNameBuilder.extractRawTableName(tableName);
        Map<ServerInstance, List<String>> offlineRoutingTable = TableNameBuilder.isOfflineTableResource(tableName) ? routingTable : null;
        Map<ServerInstance, List<String>> realtimeRoutingTable = TableNameBuilder.isRealtimeTableResource(tableName) ? routingTable : null;
        BrokerRequest offlineBrokerRequest = TableNameBuilder.isOfflineTableResource(tableName) ? brokerRequest : null;
        BrokerRequest realtimeBrokerRequest = TableNameBuilder.isRealtimeTableResource(tableName) ? brokerRequest : null;
        CommonConstants.Helix.TableType tableType = TableNameBuilder.getTableTypeFromTableName(tableName);
        AsyncQueryResponse asyncQueryResponse =
                doWithRetries(pinotRetryCount, (requestId) -> queryRouter.submitQuery(requestId, rawTableName, offlineBrokerRequest, offlineRoutingTable, realtimeBrokerRequest, realtimeRoutingTable, connectionTimeoutInMillis));
        try {
            Map<ServerRoutingInstance, ServerResponse> response = asyncQueryResponse.getResponse();
            Map<ServerInstance, DataTable> dataTableMap = new HashMap<>();
            for (Map.Entry<ServerRoutingInstance, ServerResponse> entry : response.entrySet()) {
                ServerResponse serverResponse = entry.getValue();
                DataTable dataTable = serverResponse.getDataTable();
                dataTableMap.put(toServerInstance(entry.getKey()), dataTable);
            }
            return dataTableMap;
        }
        catch (InterruptedException e) {
            throw new PinotException(PINOT_EXCEPTION, Optional.of(query), "Pinot query execution was interrupted", e);
        }
    }

    private static ServerInstance toServerInstance(ServerRoutingInstance serverRoutingInstance)
    {
        return new ServerInstance(InstanceConfig.toInstanceConfig(format("%s_%s_%s", SERVER_INSTANCE_PREFIX, serverRoutingInstance.getHostname(), serverRoutingInstance.getPort())));
    }

    private <T> T doWithRetries(int retries, Function<Long, T> caller)
    {
        PinotException firstError = null;
        for (int i = 0; i < retries; ++i) {
            try {
                return caller.apply(requestIdGenerator.getAndIncrement());
            }
            catch (PinotException e) {
                if (firstError == null) {
                    firstError = e;
                }
                if (!e.isRetriable()) {
                    throw e;
                }
            }
        }
        throw firstError;
    }
}
