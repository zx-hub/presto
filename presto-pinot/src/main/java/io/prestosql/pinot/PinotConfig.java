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

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import io.airlift.configuration.Config;
import io.airlift.units.Duration;
import io.airlift.units.MinDuration;

import javax.validation.constraints.NotNull;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;

public class PinotConfig
{
    private static final Splitter LIST_SPLITTER = Splitter.on(",").trimResults().omitEmptyStrings();

    private int maxConnectionsPerServer = 30;

    private List<String> controllerUrls = ImmutableList.of();

    private Duration idleTimeout = new Duration(5, TimeUnit.MINUTES);
    private Duration connectionTimeout = new Duration(1, TimeUnit.MINUTES);
    private Duration requestTimeout = new Duration(30, TimeUnit.SECONDS);

    private int threadPoolSize = 30;
    private int minConnectionsPerServer = 10;
    private int maxBacklogPerServer = 30;
    private int estimatedSizeInBytesForNonNumericColumn = 20;
    private Duration metadataCacheExpiry = new Duration(2, TimeUnit.MINUTES);

    private boolean preferBrokerQueries;
    private boolean forbidSegmentQueries;
    private int segmentsPerSplit = 1;
    private int fetchRetryCount = 2;
    private int nonAggregateLimitForBrokerQueries = 25_000;

    @NotNull
    public List<String> getControllerUrls()
    {
        return controllerUrls;
    }

    @Config("pinot.controller-urls")
    public PinotConfig setControllerUrls(String controllerUrl)
    {
        this.controllerUrls = LIST_SPLITTER.splitToList(controllerUrl);
        return this;
    }

    @NotNull
    public int getThreadPoolSize()
    {
        return threadPoolSize;
    }

    @Config("pinot.thread-pool-size")
    public PinotConfig setThreadPoolSize(int threadPoolSize)
    {
        this.threadPoolSize = threadPoolSize;
        return this;
    }

    @NotNull
    public int getMinConnectionsPerServer()
    {
        return minConnectionsPerServer;
    }

    @Config("pinot.min-connections-per-server")
    public PinotConfig setMinConnectionsPerServer(int minConnectionsPerServer)
    {
        this.minConnectionsPerServer = minConnectionsPerServer;
        return this;
    }

    @NotNull
    public int getMaxConnectionsPerServer()
    {
        return maxConnectionsPerServer;
    }

    @Config("pinot.max-connections-per-server")
    public PinotConfig setMaxConnectionsPerServer(int maxConnectionsPerServer)
    {
        this.maxConnectionsPerServer = maxConnectionsPerServer;
        return this;
    }

    @NotNull
    public int getMaxBacklogPerServer()
    {
        return maxBacklogPerServer;
    }

    @Config("pinot.max-backlog-per-server")
    public PinotConfig setMaxBacklogPerServer(int maxBacklogPerServer)
    {
        this.maxBacklogPerServer = maxBacklogPerServer;
        return this;
    }

    @MinDuration("15s")
    @NotNull
    public Duration getIdleTimeout()
    {
        return idleTimeout;
    }

    @Config("pinot.idle-timeout")
    public PinotConfig setIdleTimeout(Duration idleTimeout)
    {
        this.idleTimeout = idleTimeout;
        return this;
    }

    @MinDuration("15s")
    @NotNull
    public Duration getConnectionTimeout()
    {
        return connectionTimeout;
    }

    @Config("pinot.connection-timeout")
    public PinotConfig setConnectionTimeout(Duration connectionTimeout)
    {
        this.connectionTimeout = connectionTimeout;
        return this;
    }

    @MinDuration("15s")
    @NotNull
    public Duration getRequestTimeout()
    {
        return requestTimeout;
    }

    @Config("pinot.request-timeout")
    public PinotConfig setRequestTimeout(Duration requestTimeout)
    {
        this.requestTimeout = requestTimeout;
        return this;
    }

    @MinDuration("0s")
    @NotNull
    public Duration getMetadataCacheExpiry()
    {
        return metadataCacheExpiry;
    }

    @Config("pinot.metadata-expiry")
    public PinotConfig setMetadataCacheExpiry(Duration metadataCacheExpiry)
    {
        this.metadataCacheExpiry = metadataCacheExpiry;
        return this;
    }

    @NotNull
    public int getEstimatedSizeInBytesForNonNumericColumn()
    {
        return estimatedSizeInBytesForNonNumericColumn;
    }

    @Config("pinot.estimated-size-in-bytes-for-non-numeric-column")
    public PinotConfig setEstimatedSizeInBytesForNonNumericColumn(int estimatedSizeInBytesForNonNumericColumn)
    {
        this.estimatedSizeInBytesForNonNumericColumn = estimatedSizeInBytesForNonNumericColumn;
        return this;
    }

    public boolean isPreferBrokerQueries()
    {
        return preferBrokerQueries;
    }

    @Config("pinot.prefer-broker-queries")
    public PinotConfig setPreferBrokerQueries(boolean preferBrokerQueries)
    {
        this.preferBrokerQueries = preferBrokerQueries;
        return this;
    }

    public boolean isForbidSegmentQueries()
    {
        return forbidSegmentQueries;
    }

    @Config("pinot.forbid-segment-queries")
    public PinotConfig setForbidSegmentQueries(boolean forbidSegmentQueries)
    {
        this.forbidSegmentQueries = forbidSegmentQueries;
        return this;
    }

    public int getSegmentsPerSplit()
    {
        return this.segmentsPerSplit;
    }

    @Config("pinot.segments-per-split")
    public PinotConfig setSegmentsPerSplit(int segmentsPerSplit)
    {
        checkArgument(segmentsPerSplit > 0, "Segments per split must be greater than zero");
        this.segmentsPerSplit = segmentsPerSplit;
        return this;
    }

    public int getFetchRetryCount()
    {
        return fetchRetryCount;
    }

    @Config("pinot.fetch-retry-count")
    public PinotConfig setFetchRetryCount(int fetchRetryCount)
    {
        this.fetchRetryCount = fetchRetryCount;
        return this;
    }

    public int getNonAggregateLimitForBrokerQueries()
    {
        return nonAggregateLimitForBrokerQueries;
    }

    @Config("pinot.non-aggregate-limit-for-broker-queries")
    public PinotConfig setNonAggregateLimitForBrokerQueries(int nonAggregateLimitForBrokerQueries)
    {
        this.nonAggregateLimitForBrokerQueries = nonAggregateLimitForBrokerQueries;
        return this;
    }
}
