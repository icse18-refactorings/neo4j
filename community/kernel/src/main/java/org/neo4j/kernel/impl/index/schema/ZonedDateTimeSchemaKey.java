/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.index.schema;

import java.time.ZoneId;
import java.time.ZoneOffset;

import org.neo4j.kernel.impl.store.TimeZones;
import org.neo4j.values.storable.DateTimeValue;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.Values;

import static java.lang.String.format;

/**
 * Includes value and entity id (to be able to handle non-unique values). A value can be any {@link DateTimeValue}.
 *
 * With these keys the DateTimeValues are sorted by UTC time, and then by time zone. Time zones are sorted so that
 * DateTimeValues with zoneOffset come first (sorted by the zoneOffset), followed by DateTimeValues with zoneIds,
 * sorted by zoneNumber.
 */
class ZonedDateTimeSchemaKey extends ComparableNativeSchemaKey<ZonedDateTimeSchemaKey>
{
    static final int SIZE =
            Long.BYTES +    /* epochSecond */
            Integer.BYTES + /* nanoOfSecond */
            Integer.BYTES + /* timeZone */
            Long.BYTES;     /* entityId */

    int nanoOfSecond;
    long epochSecondUTC;
    short zoneId;
    int zoneOffsetSeconds;

    @Override
    public Value asValue()
    {
        return TimeZones.validZoneId( zoneId ) ?
            DateTimeValue.datetime( epochSecondUTC, nanoOfSecond, ZoneId.of( TimeZones.map( zoneId ) ) ) :
            DateTimeValue.datetime( epochSecondUTC, nanoOfSecond, ZoneOffset.ofTotalSeconds( zoneOffsetSeconds ) );
    }

    @Override
    public void initValueAsLowest()
    {
        epochSecondUTC = Long.MIN_VALUE;
        nanoOfSecond = Integer.MIN_VALUE;
        zoneId = Short.MIN_VALUE;
        zoneOffsetSeconds = Integer.MIN_VALUE;
    }

    @Override
    public void initValueAsHighest()
    {
        epochSecondUTC = Long.MAX_VALUE;
        nanoOfSecond = Integer.MAX_VALUE;
        zoneId = Short.MAX_VALUE;
        zoneOffsetSeconds = Integer.MAX_VALUE;
    }

    @Override
    public int compareValueTo( ZonedDateTimeSchemaKey other )
    {
        int compare = Long.compare( epochSecondUTC, other.epochSecondUTC );
        if ( compare == 0 )
        {
            compare = Integer.compare( nanoOfSecond, other.nanoOfSecond );
            if ( compare == 0 && ( differentValidZoneId( other ) || differentValidZoneOffset( other ) ) )
            {
                // In the rare case of comparing the same instant in different time zones, we settle for
                // mapping to values and comparing using the general values comparator.
                compare = Values.COMPARATOR.compare( asValue(), other.asValue() );
            }
        }
        return compare;
    }

    @Override
    public String toString()
    {
        return format( "value=%s,entityId=%d,epochSecond=%d,nanoOfSecond=%d,zoneId=%d,zoneOffset=%d",
                asValue(), getEntityId(), epochSecondUTC, nanoOfSecond, zoneId, zoneOffsetSeconds );
    }

    @Override
    public void writeDateTime( long epochSecondUTC, int nano, int offsetSeconds )
    {
        this.epochSecondUTC = epochSecondUTC;
        this.nanoOfSecond = nano;
        this.zoneOffsetSeconds = offsetSeconds;
        this.zoneId = -1;
    }

    @Override
    public void writeDateTime( long epochSecondUTC, int nano, String zoneId )
    {
        this.epochSecondUTC = epochSecondUTC;
        this.nanoOfSecond = nano;
        this.zoneId = TimeZones.map( zoneId );
        this.zoneOffsetSeconds = 0;
    }

    @Override
    protected Value assertCorrectType( Value value )
    {
        if ( !(value instanceof DateTimeValue) )
        {
            throw new IllegalArgumentException(
                    "Key layout does only support DateTimeValue, tried to create key from " + value );
        }
        return value;
    }

    // We need to check validity upfront without throwing exceptions, because the PageCursor might give garbage bytes
    private boolean differentValidZoneOffset( ZonedDateTimeSchemaKey other )
    {
        return zoneOffsetSeconds != other.zoneOffsetSeconds &&
                TimeZones.validZoneOffset( zoneOffsetSeconds ) && TimeZones.validZoneOffset( other.zoneOffsetSeconds );
    }

    // We need to check validity upfront without throwing exceptions, because the PageCursor might give garbage bytes
    private boolean differentValidZoneId( ZonedDateTimeSchemaKey other )
    {
        return zoneId != other.zoneId && TimeZones.validZoneId( zoneId ) && TimeZones.validZoneId( other.zoneId );
    }
}
