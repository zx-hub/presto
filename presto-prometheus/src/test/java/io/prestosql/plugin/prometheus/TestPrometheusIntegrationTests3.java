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

import io.airlift.log.Logger;
import io.prestosql.Session;
import io.prestosql.testing.AbstractTestQueryFramework;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.testing.MaterializedRow;
import io.prestosql.testing.QueryRunner;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.testng.annotations.Test;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static io.prestosql.plugin.prometheus.PrometheusQueryRunner.createPrometheusClient;
import static io.prestosql.plugin.prometheus.PrometheusQueryRunner.createPrometheusQueryRunner;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Integration tests against Prometheus container
 */
@Test(priority = 1, singleThreaded = true)
public class TestPrometheusIntegrationTests3
        extends AbstractTestQueryFramework
{
    private PrometheusServer server;
    private PrometheusClient client;
    private Session session;
    private QueryRunner runner;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        this.server = new PrometheusServer();
        this.client = createPrometheusClient(server);
        return createPrometheusQueryRunner(server);
    }

    @Test
    public void testConfirmMetricAvailableAndCheckUp()
            throws Exception
    {
        final Integer maxTries = 60;
        final Integer timeBetweenTriesMillis = 1000;
        runner = createQueryRunner();
        session = runner.getDefaultSession();
        int tries = 0;
        final OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
        String prometheusServer = server.getAddress().toString();
        HttpUrl.Builder urlBuilder = HttpUrl.parse("http://" + prometheusServer + "/api/v1/query").newBuilder();
        urlBuilder.addQueryParameter("query", "up[1d]");
        String url = urlBuilder.build().toString();
        Request request = new Request.Builder()
                .url(url)
                .build();
        String responseBody;
        // this seems to be a reliable way to ensure Prometheus has `up` metric data
        while (tries < maxTries) {
            responseBody = httpClient.newCall(request).execute().body().string();
            if (responseBody.contains("values")) {
                Logger log = Logger.get(TestPrometheusIntegrationTests3.class);
                log.info("prometheus response: %s", responseBody);
                break;
            }
            Thread.sleep(timeBetweenTriesMillis);
            tries++;
        }
        if (tries == maxTries) {
            assertTrue(false, "Prometheus container not available for metrics query in " + maxTries * timeBetweenTriesMillis + " milliseconds.");
        }
        // now we're making sure the client is ready
        tries = 0;
        while (tries < maxTries) {
            if (session != null && runner.tableExists(session, "up")) {
                break;
            }
            Thread.sleep(timeBetweenTriesMillis);
            tries++;
        }
        if (tries == maxTries) {
            assertTrue(false, "Prometheus container, or client, not available for metrics query in " + maxTries * timeBetweenTriesMillis + " milliseconds.");
        }

        PrometheusTimeMachine.useFixedClockAt(LocalDateTime.now()); // must set time to now() as other tests may have set it
        MaterializedResult results = runner.execute(session, "SELECT * FROM prometheus.default.up LIMIT 1").toTestTypes();
        assertEquals(results.getRowCount(), 1);
        MaterializedRow row = results.getMaterializedRows().get(0);
        assertEquals(row.getField(0).toString(), "{instance=localhost:9090, __name__=up, job=prometheus}");
    }

    @Test(priority = 2, dependsOnMethods = "testConfirmMetricAvailableAndCheckUp")
    public void testPushDown()
            throws Exception
    {
        // default interval on the `up` metric that Prometheus records on itself is 15 seconds, so this should only yield one row
        MaterializedResult results = runner.execute(session, "SELECT * FROM prometheus.default.up WHERE timestamp > (NOW() - INTERVAL '15' SECOND)").toTestTypes();
        assertEquals(results.getRowCount(), 1);
    }
}
