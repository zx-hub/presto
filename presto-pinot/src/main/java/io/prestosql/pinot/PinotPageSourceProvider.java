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
package io.prestosql.pinot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.prestosql.pinot.client.PinotClient;
import io.prestosql.pinot.client.PinotQueryClient;
import io.prestosql.pinot.query.DynamicTable;
import io.prestosql.pinot.query.PinotQuery;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.connector.ConnectorPageSourceProvider;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTransactionHandle;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;

import static io.prestosql.pinot.query.DynamicTablePqlExtractor.extractPql;
import static io.prestosql.pinot.query.PinotQueryBuilder.generatePql;
import static java.util.Objects.requireNonNull;

public class PinotPageSourceProvider
        implements ConnectorPageSourceProvider
{
    private final PinotConfig pinotConfig;
    private final PinotQueryClient pinotQueryClient;
    private final PinotClient clusterInfoFetcher;
    private final ObjectMapper objectMapper;

    @Inject
    public PinotPageSourceProvider(
            PinotConfig pinotConfig,
            PinotClient clusterInfoFetcher,
            ObjectMapper objectMapper)
    {
        this.pinotConfig = requireNonNull(pinotConfig, "pinotConfig is null");
        this.pinotQueryClient = new PinotQueryClient(pinotConfig);
        this.clusterInfoFetcher = requireNonNull(clusterInfoFetcher, "cluster info fetcher is null");
        this.objectMapper = requireNonNull(objectMapper, "object mapper is null");
    }

    @Override
    public ConnectorPageSource createPageSource(
            ConnectorTransactionHandle transactionHandle,
            ConnectorSession session,
            ConnectorSplit split,
            ConnectorTableHandle tableHandle,
            List<ColumnHandle> columns)
    {
        requireNonNull(split, "split is null");

        PinotSplit pinotSplit = (PinotSplit) split;

        List<PinotColumnHandle> handles = new ArrayList<>();
        for (ColumnHandle handle : columns) {
            handles.add((PinotColumnHandle) handle);
        }
        PinotTableHandle pinotTableHandle = (PinotTableHandle) tableHandle;
        String query = generatePql(pinotTableHandle, handles, pinotSplit.getSuffix(), pinotSplit.getTimePredicate());

        switch (pinotSplit.getSplitType()) {
            case SEGMENT:
                return new PinotSegmentPageSource(
                        session,
                        this.pinotConfig,
                        this.pinotQueryClient,
                        pinotSplit,
                        handles,
                        query);
            case BROKER:
                PinotQuery pinotQuery;
                if (pinotTableHandle.getQuery().isPresent()) {
                    DynamicTable dynamicTable = pinotTableHandle.getQuery().get();
                    pinotQuery = new PinotQuery(dynamicTable.getTableName(),
                            extractPql(dynamicTable, pinotTableHandle.getConstraint(), handles),
                            dynamicTable.getGroupingColumns().size());
                }
                else {
                    pinotQuery = new PinotQuery(pinotTableHandle.getTableName(), query, 0);
                }

                return new PinotBrokerPageSource(
                        session,
                        pinotQuery,
                        handles,
                        clusterInfoFetcher);
            default:
                throw new UnsupportedOperationException("Unknown Pinot split type: " + pinotSplit.getSplitType());
        }
    }
}
