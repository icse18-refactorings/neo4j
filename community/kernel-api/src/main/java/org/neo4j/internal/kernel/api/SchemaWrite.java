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
package org.neo4j.internal.kernel.api;

import org.neo4j.internal.kernel.api.exceptions.schema.SchemaKernelException;
import org.neo4j.internal.kernel.api.schema.LabelSchemaDescriptor;
import org.neo4j.internal.kernel.api.schema.RelationTypeSchemaDescriptor;
import org.neo4j.internal.kernel.api.schema.constraints.ConstraintDescriptor;

/**
 * Surface for creating and dropping indexes and constraints.
 */
public interface SchemaWrite
{
    /**
     * Create index from label descriptor
     *
     * @param descriptor description of the index
     * @return the newly created index
     */
    CapableIndexReference indexCreate( LabelSchemaDescriptor descriptor ) throws SchemaKernelException;

    /**
     * Drop the given index
     *
     * @param index the index to drop
     */
    void indexDrop( CapableIndexReference index ) throws SchemaKernelException;

    /**
     * Create unique property constraint
     *
     * @param descriptor description of the constraint
     */
    ConstraintDescriptor uniquePropertyConstraintCreate( LabelSchemaDescriptor descriptor ) throws SchemaKernelException;

    /**
     * Create node key constraint
     *
     * @param descriptor description of the constraint
     */
    ConstraintDescriptor nodeKeyConstraintCreate( LabelSchemaDescriptor descriptor ) throws SchemaKernelException;

    /**
     * Create node property existence constraint
     *
     * @param descriptor description of the constraint
     */
    ConstraintDescriptor nodePropertyExistenceConstraintCreate( LabelSchemaDescriptor descriptor ) throws SchemaKernelException;

    /**
     * Create relationship property existence constraint
     *
     * @param descriptor description of the constraint
     */
    ConstraintDescriptor relationshipPropertyExistenceConstraintCreate( RelationTypeSchemaDescriptor descriptor )
            throws SchemaKernelException;

    /**
     * Drop constraint
     *
     * @param descriptor description of the constraint
     */
    void constraintDrop( ConstraintDescriptor descriptor ) throws SchemaKernelException;
}
