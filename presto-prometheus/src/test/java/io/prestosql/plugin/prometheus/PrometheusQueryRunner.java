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

import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.airlift.log.Logging;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.testing.DistributedQueryRunner;
import io.prestosql.type.InternalTypeManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import static io.airlift.testing.Closeables.closeAllSuppress;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.plugin.prometheus.MetadataUtil.METRIC_CODEC;
import static io.prestosql.testing.TestingSession.testSessionBuilder;

public final class PrometheusQueryRunner
{
    private static final Metadata METADATA = createTestMetadataManager();
    private static final TypeManager TYPE_MANAGER = new InternalTypeManager(METADATA);
    private static URI prometheusURI;

    private PrometheusQueryRunner() {}

    public static DistributedQueryRunner createPrometheusQueryRunner(PrometheusServer server)
            throws Exception
    {
        DistributedQueryRunner queryRunner = null;
        try {
            queryRunner = DistributedQueryRunner.builder(createSession()).build();

            queryRunner.installPlugin(new PrometheusPlugin());
            Map<String, String> properties = ImmutableMap.of(
                    "prometheus.uri", prometheusURI.toString());
            queryRunner.createCatalog("prometheus", "prometheus", properties);
            return queryRunner;
        }
        catch (Throwable e) {
            closeAllSuppress(e, queryRunner);
            throw e;
        }
    }

    public static Session createSession()
    {
        return testSessionBuilder()
                .setCatalog("prometheus")
                .setSchema("default")
                .build();
    }

    public static PrometheusClient createPrometheusClient(PrometheusServer server)
            throws URISyntaxException
    {
        prometheusURI = new URI("http://" + server.getAddress().getHost() + ":" + server.getAddress().getPort() + "/");
        PrometheusConnectorConfig config = new PrometheusConnectorConfig();
        config.setPrometheusURI(prometheusURI);
        config.setQueryChunkSizeDuration(Duration.valueOf("1d"));
        config.setMaxQueryRangeDuration(Duration.valueOf("21d"));
        config.setCacheDuration(Duration.valueOf("30s"));
        return new PrometheusClient(config, METRIC_CODEC, TYPE_MANAGER);
    }

    public static void main(String[] args)
            throws Exception
    {
        Logging.initialize();
        DistributedQueryRunner queryRunner = createPrometheusQueryRunner(new PrometheusServer());
        Thread.sleep(10);
        Logger log = Logger.get(PrometheusQueryRunner.class);
        log.info("======== SERVER STARTED ========");
        log.info("\n====\n%s\n====", queryRunner.getCoordinator().getBaseUrl());
    }
}
