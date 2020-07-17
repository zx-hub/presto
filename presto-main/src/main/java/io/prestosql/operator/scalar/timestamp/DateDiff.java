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
package io.prestosql.operator.scalar.timestamp;

import io.airlift.slice.Slice;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.function.Description;
import io.prestosql.spi.function.LiteralParameter;
import io.prestosql.spi.function.LiteralParameters;
import io.prestosql.spi.function.ScalarFunction;
import io.prestosql.spi.function.SqlType;
import io.prestosql.spi.type.LongTimestamp;
import io.prestosql.spi.type.StandardTypes;
import org.joda.time.chrono.ISOChronology;

import static io.prestosql.operator.scalar.DateTimeFunctions.getTimestampField;
import static io.prestosql.type.Timestamps.scaleEpochMicrosToMillis;
import static io.prestosql.util.DateTimeZoneIndex.getChronology;

@Description("Difference of the given times in the given unit")
@ScalarFunction("date_diff")
public class DateDiff
{
    private DateDiff() {}

    @LiteralParameters({"x", "p"})
    @SqlType(StandardTypes.BIGINT)
    public static long diff(
            @LiteralParameter("p") long precision,
            ConnectorSession session,
            @SqlType("varchar(x)") Slice unit,
            @SqlType("timestamp(p)") long timestamp1,
            @SqlType("timestamp(p)") long timestamp2)
    {
        long epochMillis1 = timestamp1;
        long epochMillis2 = timestamp2;
        if (precision > 3) {
            epochMillis1 = scaleEpochMicrosToMillis(timestamp1);
            epochMillis2 = scaleEpochMicrosToMillis(timestamp2);
        }

        ISOChronology chronology = ISOChronology.getInstanceUTC();
        if (session.isLegacyTimestamp()) {
            chronology = getChronology(session.getTimeZoneKey());
        }

        return getTimestampField(chronology, unit).getDifferenceAsLong(epochMillis2, epochMillis1);
    }

    @LiteralParameters({"x", "p"})
    @SqlType(StandardTypes.BIGINT)
    public static long diff(
            ConnectorSession session,
            @SqlType("varchar(x)") Slice unit,
            @SqlType("timestamp(p)") LongTimestamp timestamp1,
            @SqlType("timestamp(p)") LongTimestamp timestamp2)
    {
        // smallest unit of date_diff is "millisecond", so anything in the fraction is irrelevant
        return diff(6, session, unit, timestamp1.getEpochMicros(), timestamp2.getEpochMicros());
    }
}
