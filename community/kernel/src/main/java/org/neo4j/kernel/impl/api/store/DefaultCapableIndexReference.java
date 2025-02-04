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
package org.neo4j.kernel.impl.api.store;

import java.util.Arrays;

import org.neo4j.internal.kernel.api.CapableIndexReference;
import org.neo4j.internal.kernel.api.IndexCapability;
import org.neo4j.internal.kernel.api.IndexOrder;
import org.neo4j.internal.kernel.api.IndexValueCapability;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.internal.kernel.api.schema.LabelSchemaDescriptor;
import org.neo4j.kernel.api.schema.index.SchemaIndexDescriptor;
import org.neo4j.kernel.api.schema.index.SchemaIndexDescriptorFactory;
import org.neo4j.values.storable.ValueGroup;

public class DefaultCapableIndexReference implements CapableIndexReference
{
    private final int label;
    private final int[] properties;
    private final boolean unique;
    private final IndexProvider.Descriptor providerDescriptor;
    private final IndexCapability capability;

    public DefaultCapableIndexReference( boolean unique, IndexCapability indexCapability,
                IndexProvider.Descriptor providerDescriptor, int label, int... properties )
    {
        this.unique = unique;
        this.capability = indexCapability;
        this.label = label;
        this.providerDescriptor = providerDescriptor;
        this.properties = properties;
    }

    @Override
    public boolean isUnique()
    {
        return unique;
    }

    @Override
    public int label()
    {
        return label;
    }

    @Override
    public int[] properties()
    {
        return properties;
    }

    @Override
    public String providerKey()
    {
        return providerDescriptor.getKey();
    }

    @Override
    public String providerVersion()
    {
        return providerDescriptor.getVersion();
    }

    @Override
    public IndexOrder[] orderCapability( ValueGroup... valueGroups )
    {
        return capability.orderCapability( valueGroups );
    }

    @Override
    public IndexValueCapability valueCapability( ValueGroup... valueGroups )
    {
        return capability.valueCapability( valueGroups );
    }

    @Override
    public boolean equals( Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( o == null || getClass() != o.getClass() )
        {
            return false;
        }

        DefaultCapableIndexReference that = (DefaultCapableIndexReference) o;

        return label == that.label && unique == that.unique && Arrays.equals( properties, that.properties );
    }

    @Override
    public String toString()
    {
        return String.format( "Index(%d:%s)", label, Arrays.toString( properties ) );
    }

    @Override
    public int hashCode()
    {
        int result = label;
        result = 31 * result + Arrays.hashCode( properties );
        result = 31 * result + (unique ? 1 : 0);
        result = 31 * result + (providerDescriptor != null ? providerDescriptor.hashCode() : 0);
        return result;
    }

    public static CapableIndexReference fromDescriptor( SchemaIndexDescriptor descriptor )
    {
        boolean unique =  descriptor.type() == SchemaIndexDescriptor.Type.UNIQUE;
        final LabelSchemaDescriptor schema = descriptor.schema();
        return new DefaultCapableIndexReference( unique, IndexCapability.NO_CAPABILITY, null,
                schema.getLabelId(), schema.getPropertyIds() );
    }

    public static SchemaIndexDescriptor fromReference( CapableIndexReference index )
    {
        if ( index.isUnique() )
        {
            return SchemaIndexDescriptorFactory.uniqueForLabel( index.label(), index.properties() );
        }
        else
        {
            return SchemaIndexDescriptorFactory.forLabel( index.label(), index.properties() );
        }
    }
}
