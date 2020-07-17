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

import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.airlift.log.Logging;
import io.prestosql.Session;
import io.prestosql.metadata.SessionPropertyManager;
import io.prestosql.testing.DistributedQueryRunner;

import java.util.Map;

import static io.prestosql.testing.TestingSession.testSessionBuilder;

public class PinotQueryRunner
{
    private PinotQueryRunner()
    {
    }

    public static DistributedQueryRunner createPinotQueryRunner(Map<String, String> extraProperties, Map<String, String> extraPinotProperties)
            throws Exception
    {
        DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(createSession("default"))
                .setNodeCount(2)
                .setExtraProperties(extraProperties)
                .build();
        queryRunner.installPlugin(new PinotPlugin());
        queryRunner.createCatalog("pinot", "pinot", extraPinotProperties);
        return queryRunner;
    }

    public static Session createSession(String schema)
    {
        SessionPropertyManager sessionPropertyManager = new SessionPropertyManager();
        return testSessionBuilder(sessionPropertyManager)
                .setCatalog("pinot")
                .setSchema(schema)
                .build();
    }

    public static void main(String[] args)
            throws Exception
    {
        Logging.initialize();
        Map<String, String> properties = ImmutableMap.of("http-server.http.port", "8080");
        Map<String, String> pinotProperties = ImmutableMap.<String, String>builder()
                .put("pinot.controller-urls", "localhost:9000")
                .put("pinot.segments-per-split", "10")
                .put("pinot.request-timeout", "3m")
                .build();
        DistributedQueryRunner queryRunner = createPinotQueryRunner(properties, pinotProperties);
        Thread.sleep(10);
        Logger log = Logger.get(PinotQueryRunner.class);
        log.info("======== SERVER STARTED ========");
        log.info("\n====\n%s\n====", queryRunner.getCoordinator().getBaseUrl());
    }
}
