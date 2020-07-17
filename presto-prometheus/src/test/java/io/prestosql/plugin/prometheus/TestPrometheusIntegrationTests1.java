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
import com.google.common.collect.ImmutableSet;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.RecordSet;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.TimestampType;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;

import static io.prestosql.plugin.prometheus.PrometheusQueryRunner.createPrometheusClient;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static io.prestosql.testing.TestingConnectorSession.SESSION;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Integration tests against Prometheus container
 */
@Test(singleThreaded = true)
public class TestPrometheusIntegrationTests1
{
    private static final PrometheusTableHandle RUNTIME_DETERMINED_TABLE_HANDLE = new PrometheusTableHandle("default", "up");

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
    public void testHandleErrorResponse()
            throws Exception
    {
        assertThatThrownBy(() -> client.getTableNames("undefault"))
                .isInstanceOf(PrestoException.class)
                .hasMessageContaining("Prometheus did no return metrics list (table names)");
        PrometheusTable table = client.getTable("undefault", "up");
        assertNull(table);
    }

    @Test(dependsOnMethods = "testRetrieveUpValue")
    public void testListSchemaNames()
            throws Exception
    {
        PrometheusMetadata metadata = new PrometheusMetadata(client);
        assertEquals(metadata.listSchemaNames(SESSION), ImmutableSet.of("default"));
    }

    @Test(dependsOnMethods = "testRetrieveUpValue")
    public void testGetColumnMetadata()
            throws Exception
    {
        PrometheusMetadata metadata = new PrometheusMetadata(client);
        assertEquals(metadata.getColumnMetadata(SESSION, RUNTIME_DETERMINED_TABLE_HANDLE, new PrometheusColumnHandle("text", createUnboundedVarcharType(), 0)),
                new ColumnMetadata("text", createUnboundedVarcharType()));

        // prometheus connector assumes that the table handle and column handle are
        // properly formed, so it will return a metadata object for any
        // PrometheusTableHandle and PrometheusColumnHandle passed in.  This is on because
        // it is not possible for the Presto Metadata system to create the handles
        // directly.
    }

    @Test(expectedExceptions = PrestoException.class, dependsOnMethods = "testRetrieveUpValue")
    public void testCreateTable()
            throws Exception
    {
        PrometheusMetadata metadata = new PrometheusMetadata(client);
        metadata.createTable(
                SESSION,
                new ConnectorTableMetadata(
                        new SchemaTableName("default", "foo"),
                        ImmutableList.of(new ColumnMetadata("text", createUnboundedVarcharType()))),
                false);
    }

    @Test(expectedExceptions = PrestoException.class, dependsOnMethods = "testRetrieveUpValue")
    public void testDropTableTable()
            throws Exception
    {
        PrometheusMetadata metadata = new PrometheusMetadata(client);
        metadata.dropTable(SESSION, RUNTIME_DETERMINED_TABLE_HANDLE);
    }

    @Test(dependsOnMethods = "testRetrieveUpValue")
    public void testGetColumnTypes()
            throws Exception
    {
        URI dataUri = new URI("http://" + server.getAddress().getHost() + ":" + server.getAddress().getPort() + "/");
        RecordSet recordSet = new PrometheusRecordSet(new PrometheusSplit(dataUri), ImmutableList.of(
                new PrometheusColumnHandle("labels", createUnboundedVarcharType(), 0),
                new PrometheusColumnHandle("value", DoubleType.DOUBLE, 1),
                new PrometheusColumnHandle("timestamp", TimestampType.TIMESTAMP, 2)));
        assertEquals(recordSet.getColumnTypes(), ImmutableList.of(createUnboundedVarcharType(), DoubleType.DOUBLE, TimestampType.TIMESTAMP));

        recordSet = new PrometheusRecordSet(new PrometheusSplit(dataUri), ImmutableList.of(
                new PrometheusColumnHandle("value", BIGINT, 1),
                new PrometheusColumnHandle("text", createUnboundedVarcharType(), 0)));
        assertEquals(recordSet.getColumnTypes(), ImmutableList.of(BIGINT, createUnboundedVarcharType()));

        recordSet = new PrometheusRecordSet(new PrometheusSplit(dataUri), ImmutableList.of(
                new PrometheusColumnHandle("value", BIGINT, 1),
                new PrometheusColumnHandle("value", BIGINT, 1),
                new PrometheusColumnHandle("text", createUnboundedVarcharType(), 0)));
        assertEquals(recordSet.getColumnTypes(), ImmutableList.of(BIGINT, BIGINT, createUnboundedVarcharType()));

        recordSet = new PrometheusRecordSet(new PrometheusSplit(dataUri), ImmutableList.of());
        assertEquals(recordSet.getColumnTypes(), ImmutableList.of());
    }
}
