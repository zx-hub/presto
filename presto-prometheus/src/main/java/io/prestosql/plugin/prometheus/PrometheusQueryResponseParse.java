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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.prestosql.spi.PrestoException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.prestosql.plugin.prometheus.PrometheusErrorCode.PROMETHEUS_PARSE_ERROR;

public class PrometheusQueryResponseParse
{
    private final InputStream response;
    private boolean status;

    private String error;
    private String errorType;
    private List<String> warnings; //TODO not parsing warnings for now
    private String resultType;
    private String result;
    private List<PrometheusMetricResult> results;
    private PrometheusTimeSeriesValue stringOrScalarResult;

    public PrometheusQueryResponseParse(InputStream response)
            throws IOException
    {
        this.response = response;
        parse();
    }

    private boolean parse()
            throws IOException
    {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        JsonParser parser = new JsonFactory().createParser(response);
        while (!parser.isClosed()) {
            JsonToken jsonToken = parser.nextToken();
            if (JsonToken.FIELD_NAME.equals(jsonToken)) {
                if (parser.getCurrentName().equals("status")) {
                    parser.nextToken();
                    if (parser.getValueAsString().equals("success")) {
                        this.status = true;
                        while (!parser.isClosed()) {
                            parser.nextToken();
                            if (JsonToken.FIELD_NAME.equals(jsonToken)) {
                                if (parser.getCurrentName().equals("resultType")) {
                                    parser.nextToken();
                                    resultType = parser.getValueAsString();
                                }
                                if (parser.getCurrentName().equals("result")) {
                                    parser.nextToken();
                                    ArrayNode node = mapper.readTree(parser);
                                    result = node.toString();
                                    break;
                                }
                            }
                        }
                    }
                    else {
                        //error path
                        String parsedStatus = parser.getValueAsString();
                        parser.nextToken();
                        parser.nextToken();
                        this.errorType = parser.getValueAsString();
                        parser.nextToken();
                        parser.nextToken();
                        error = parser.getValueAsString();
                        throw new PrestoException(PROMETHEUS_PARSE_ERROR, "Unable to parse Prometheus response: " + parsedStatus + " " + errorType + " " + error);
                    }
                }
            }
            if (result != null) {
                break;
            }
        }
        if (result != null && resultType != null) {
            switch (resultType) {
                case "matrix":
                case "vector":
                    results = mapper.readValue(result, new TypeReference<List<PrometheusMetricResult>>() {});
                    break;
                case "scalar":
                case "string":
                    stringOrScalarResult = mapper.readValue(result, new TypeReference<PrometheusTimeSeriesValue>() {});
                    Map<String, String> madeUpMetricHeader = new HashMap<>();
                    madeUpMetricHeader.put("__name__", resultType);
                    PrometheusTimeSeriesValueArray timeSeriesValues = new PrometheusTimeSeriesValueArray(Arrays.asList(stringOrScalarResult));
                    results = Arrays.asList(new PrometheusMetricResult(madeUpMetricHeader, timeSeriesValues));
            }
        }
        return true;
    }

    public String getError()
    {
        return error;
    }

    public String getErrorType()
    {
        return errorType;
    }

    public List<PrometheusMetricResult> getResults()
    {
        return results;
    }

    public boolean getStatus()
    {
        return this.status;
    }
}
