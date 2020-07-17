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
package io.prestosql.operator.scalar.timestamptz;

import io.prestosql.spi.function.Description;
import io.prestosql.spi.function.LiteralParameters;
import io.prestosql.spi.function.ScalarFunction;
import io.prestosql.spi.function.SqlType;
import io.prestosql.spi.type.LongTimestampWithTimeZone;
import io.prestosql.spi.type.StandardTypes;

import static io.prestosql.util.DateTimeZoneIndex.extractZoneOffsetMinutes;

@Description("Time zone hour of the given timestamp")
@ScalarFunction("timezone_hour")
public class TimeZoneHour
{
    private TimeZoneHour() {}

    @LiteralParameters("p")
    @SqlType(StandardTypes.BIGINT)
    public static long extract(@SqlType("timestamp(p) with time zone") long packedEpochMillis)
    {
        return extractZoneOffsetMinutes(packedEpochMillis) / 60;
    }

    @LiteralParameters("p")
    @SqlType(StandardTypes.BIGINT)
    public static long extract(@SqlType("timestamp(p) with time zone") LongTimestampWithTimeZone timestamp)
    {
        return extractZoneOffsetMinutes(timestamp.getEpochMillis(), timestamp.getTimeZoneKey()) / 60;
    }
}
