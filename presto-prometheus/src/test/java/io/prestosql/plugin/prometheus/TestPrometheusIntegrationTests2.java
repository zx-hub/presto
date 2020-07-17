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
package io.prestosql.plugin.prometheus;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.units.Duration;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorSplitSource;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.TableNotFoundException;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.TimestampType;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static io.prestosql.plugin.prometheus.MetadataUtil.varcharMapType;
import static io.prestosql.plugin.prometheus.PrometheusQueryRunner.createPrometheusClient;
import static io.prestosql.spi.connector.NotPartitionedPartitionHandle.NOT_PARTITIONED;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static io.prestosql.testing.TestingConnectorSession.SESSION;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Integration tests against Prometheus container
 */
@Test(singleThreaded = true)
public class TestPrometheusIntegrationTests2
{
    private static final PrometheusTableHandle RUNTIME_DETERMINED_TABLE_HANDLE = new PrometheusTableHandle("default", "up");

    private static final int NUMBER_MORE_THAN_EXPECTED_NUMBER_SPLITS = 100;

    private PrometheusServer server;
    private PrometheusClient client;

    @BeforeClass
    protected void createQueryRunner()
            throws Exception
    {
        this.server = new PrometheusServer();
        this.client = createPrometheusClient(server);
    }

    @AfterClass(alwaysRun = true)
    public final void destroy()
    {
        server.close();
    }

    @Test
    public void testRetrieveUpValue()
            throws Exception
    {
        this.server.checkServerReady(this.client);
        assertTrue(client.getTableNames("default").contains("up"), "Prometheus' own `up` metric should be available in default");
    }

    @Test(dependsOnMethods = "testRetrieveUpValue")
    public void testMetadata()
            throws Exception
    {
        assertTrue(client.getTableNames("default").contains("up"));
        PrometheusTable table = client.getTable("default", "up");
        assertNotNull(table, "table is null");
        assertEquals(table.getName(), "up");
        assertEquals(table.getColumns(), ImmutableList.of(
                new PrometheusColumn("labels", varcharMapType),
                new PrometheusColumn("timestamp", TimestampType.TIMESTAMP),
                new PrometheusColumn("value", DoubleType.DOUBLE)));
    }

    @Test(dependsOnMethods = "testRetrieveUpValue")
    public void testGetTableHandle()
            throws Exception
    {
        PrometheusMetadata metadata = new PrometheusMetadata(client);
        assertEquals(metadata.getTableHandle(SESSION, new SchemaTableName("default", "up")), RUNTIME_DETERMINED_TABLE_HANDLE);
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName("default", "unknown")));
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName("unknown", "numbers")));
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName("unknown", "unknown")));
    }

    @Test(dependsOnMethods = "testRetrieveUpValue")
    public void testGetColumnHandles()
            throws Exception
    {
        PrometheusMetadata metadata = new PrometheusMetadata(client);
        // known table
        assertEquals(metadata.getColumnHandles(SESSION, RUNTIME_DETERMINED_TABLE_HANDLE), ImmutableMap.of(
                "labels", new PrometheusColumnHandle("labels", createUnboundedVarcharType(), 0),
                "value", new PrometheusColumnHandle("value", DOUBLE, 1),
                "timestamp", new PrometheusColumnHandle("timestamp", TimestampType.TIMESTAMP, 2)));

        // unknown table
        try {
            metadata.getColumnHandles(SESSION, new PrometheusTableHandle("unknown", "unknown"));
            fail("Expected getColumnHandle of unknown table to throw a TableNotFoundException");
        }
        catch (TableNotFoundException expected) {
        }
        try {
            metadata.getColumnHandles(SESSION, new PrometheusTableHandle("default", "unknown"));
            fail("Expected getColumnHandle of unknown table to throw a TableNotFoundException");
        }
        catch (TableNotFoundException expected) {
        }
    }

    @Test(dependsOnMethods = "testRetrieveUpValue")
    public void testGetTableMetadata()
            throws Exception
    {
        PrometheusMetadata metadata = new PrometheusMetadata(client);
        // known table
        ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(SESSION, RUNTIME_DETERMINED_TABLE_HANDLE);
        assertEquals(tableMetadata.getTable(), new SchemaTableName("default", "up"));
        assertEquals(tableMetadata.getColumns(), ImmutableList.of(
                new ColumnMetadata("labels", varcharMapType),
                new ColumnMetadata("timestamp", TimestampType.TIMESTAMP),
                new ColumnMetadata("value", DOUBLE)));

        // unknown tables should produce null
        assertNull(metadata.getTableMetadata(SESSION, new PrometheusTableHandle("unknown", "unknown")));
        assertNull(metadata.getTableMetadata(SESSION, new PrometheusTableHandle("default", "unknown")));
        assertNull(metadata.getTableMetadata(SESSION, new PrometheusTableHandle("unknown", "numbers")));
    }

    @Test(dependsOnMethods = "testRetrieveUpValue")
    public void testListTables()
            throws Exception
    {
        PrometheusMetadata metadata = new PrometheusMetadata(client);
        assertTrue(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.of("default"))).contains(new SchemaTableName("default", "up")));

        // unknown schema
        assertThatThrownBy(() -> metadata.listTables(SESSION, Optional.of("unknown")))
                .isInstanceOf(PrestoException.class)
                .hasMessageContaining("Prometheus did no return metrics list (table names): ");
    }

    @Test(dependsOnMethods = "testRetrieveUpValue")
    public void testCorrectNumberOfSplitsCreated()
            throws Exception
    {
        PrometheusConnectorConfig config = new PrometheusConnectorConfig();
        config.setPrometheusURI(new URI("http://" + server.getAddress().getHost() + ":" + server.getAddress().getPort() + "/"));
        config.setMaxQueryRangeDuration(Duration.valueOf("21d"));
        config.setQueryChunkSizeDuration(Duration.valueOf("1d"));
        config.setCacheDuration(Duration.valueOf("30s"));
        PrometheusTable table = client.getTable("default", "up");
        PrometheusSplitManager splitManager = new PrometheusSplitManager(client, config);
        ConnectorSplitSource splits = splitManager.getSplits(
                null,
                null,
                (ConnectorTableHandle) new PrometheusTableHandle("default", table.getName()),
                null);
        int numSplits = splits.getNextBatch(NOT_PARTITIONED, NUMBER_MORE_THAN_EXPECTED_NUMBER_SPLITS).getNow(null).getSplits().size();
        assertEquals(numSplits, config.getMaxQueryRangeDuration().getValue(TimeUnit.SECONDS) / config.getQueryChunkSizeDuration().getValue(TimeUnit.SECONDS),
                0.001);
    }
}
