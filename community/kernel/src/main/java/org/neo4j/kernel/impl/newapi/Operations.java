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
package org.neo4j.kernel.impl.newapi;

import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

import org.neo4j.helpers.collection.CastingIterator;
import org.neo4j.helpers.collection.Iterators;
import org.neo4j.internal.kernel.api.CapableIndexReference;
import org.neo4j.internal.kernel.api.CursorFactory;
import org.neo4j.internal.kernel.api.ExplicitIndexRead;
import org.neo4j.internal.kernel.api.ExplicitIndexWrite;
import org.neo4j.internal.kernel.api.IndexOrder;
import org.neo4j.internal.kernel.api.IndexQuery;
import org.neo4j.internal.kernel.api.IndexReference;
import org.neo4j.internal.kernel.api.Locks;
import org.neo4j.internal.kernel.api.NodeLabelIndexCursor;
import org.neo4j.internal.kernel.api.Procedures;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.internal.kernel.api.SchemaRead;
import org.neo4j.internal.kernel.api.SchemaWrite;
import org.neo4j.internal.kernel.api.Token;
import org.neo4j.internal.kernel.api.Write;
import org.neo4j.internal.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.internal.kernel.api.exceptions.KernelException;
import org.neo4j.internal.kernel.api.exceptions.explicitindex.AutoIndexingKernelException;
import org.neo4j.internal.kernel.api.exceptions.explicitindex.ExplicitIndexNotFoundKernelException;
import org.neo4j.internal.kernel.api.exceptions.schema.ConstraintValidationException;
import org.neo4j.internal.kernel.api.exceptions.schema.SchemaKernelException;
import org.neo4j.internal.kernel.api.schema.LabelSchemaDescriptor;
import org.neo4j.internal.kernel.api.schema.RelationTypeSchemaDescriptor;
import org.neo4j.internal.kernel.api.schema.SchemaDescriptor;
import org.neo4j.internal.kernel.api.schema.constraints.ConstraintDescriptor;
import org.neo4j.kernel.api.SilentTokenNameLookup;
import org.neo4j.kernel.api.StatementConstants;
import org.neo4j.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.exceptions.index.IndexNotApplicableKernelException;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.schema.AlreadyConstrainedException;
import org.neo4j.kernel.api.exceptions.schema.AlreadyIndexedException;
import org.neo4j.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.DropConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.DropIndexFailureException;
import org.neo4j.kernel.api.exceptions.schema.IndexBelongsToConstraintException;
import org.neo4j.kernel.api.exceptions.schema.IndexBrokenKernelException;
import org.neo4j.kernel.api.exceptions.schema.NoSuchConstraintException;
import org.neo4j.kernel.api.exceptions.schema.NoSuchIndexException;
import org.neo4j.kernel.api.exceptions.schema.RepeatedPropertyInCompositeSchemaException;
import org.neo4j.kernel.api.exceptions.schema.UnableToValidateConstraintException;
import org.neo4j.kernel.api.exceptions.schema.UniquePropertyValueValidationException;
import org.neo4j.kernel.api.explicitindex.AutoIndexing;
import org.neo4j.kernel.api.schema.constaints.ConstraintDescriptorFactory;
import org.neo4j.kernel.api.schema.constaints.IndexBackedConstraintDescriptor;
import org.neo4j.kernel.api.schema.constaints.UniquenessConstraintDescriptor;
import org.neo4j.kernel.api.schema.index.SchemaIndexDescriptor;
import org.neo4j.kernel.api.schema.index.SchemaIndexDescriptorFactory;
import org.neo4j.kernel.api.txstate.TransactionState;
import org.neo4j.kernel.impl.api.KernelTransactionImplementation;
import org.neo4j.kernel.impl.api.state.ConstraintIndexCreator;
import org.neo4j.kernel.impl.constraints.ConstraintSemantics;
import org.neo4j.kernel.impl.index.IndexEntityType;
import org.neo4j.kernel.impl.locking.ResourceTypes;
import org.neo4j.storageengine.api.EntityType;
import org.neo4j.storageengine.api.StorageStatement;
import org.neo4j.storageengine.api.lock.ResourceType;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.Values;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static org.neo4j.internal.kernel.api.exceptions.schema.ConstraintValidationException.Phase.VALIDATION;
import static org.neo4j.internal.kernel.api.exceptions.schema.SchemaKernelException.OperationContext.CONSTRAINT_CREATION;
import static org.neo4j.internal.kernel.api.schema.SchemaDescriptorPredicates.hasProperty;
import static org.neo4j.kernel.api.StatementConstants.NO_SUCH_NODE;
import static org.neo4j.kernel.api.StatementConstants.NO_SUCH_PROPERTY_KEY;
import static org.neo4j.kernel.api.schema.index.SchemaIndexDescriptor.Type.UNIQUE;
import static org.neo4j.kernel.impl.api.store.DefaultCapableIndexReference.fromDescriptor;
import static org.neo4j.kernel.impl.locking.ResourceTypes.INDEX_ENTRY;
import static org.neo4j.kernel.impl.locking.ResourceTypes.indexEntryResourceId;
import static org.neo4j.kernel.impl.newapi.IndexTxStateUpdater.LabelChangeType.ADDED_LABEL;
import static org.neo4j.kernel.impl.newapi.IndexTxStateUpdater.LabelChangeType.REMOVED_LABEL;
import static org.neo4j.values.storable.Values.NO_VALUE;


/**
 * Collects all Kernel API operations and guards them from being used outside of transaction.
 */
public class Operations implements Write, ExplicitIndexWrite, SchemaWrite
{
    private final KernelTransactionImplementation ktx;
    private final AllStoreHolder allStoreHolder;
    private final KernelToken token;
    private final StorageStatement statement;
    private final AutoIndexing autoIndexing;
    private DefaultNodeCursor nodeCursor;
    private final IndexTxStateUpdater updater;
    private DefaultPropertyCursor propertyCursor;
    private DefaultRelationshipScanCursor relationshipCursor;
    private final DefaultCursors cursors;
    private final ConstraintIndexCreator constraintIndexCreator;
    private final ConstraintSemantics constraintSemantics;

    public Operations(
            AllStoreHolder allStoreHolder,
            IndexTxStateUpdater updater,
            StorageStatement statement,
            KernelTransactionImplementation ktx,
            KernelToken token,
            DefaultCursors cursors,
            AutoIndexing autoIndexing,
            ConstraintIndexCreator constraintIndexCreator,
            ConstraintSemantics constraintSemantics )
    {
        this.token = token;
        this.autoIndexing = autoIndexing;
        this.allStoreHolder = allStoreHolder;
        this.ktx = ktx;
        this.statement = statement;
        this.updater = updater;
        this.cursors = cursors;
        this.constraintIndexCreator = constraintIndexCreator;
        this.constraintSemantics = constraintSemantics;
    }

    public void initialize()
    {
        this.nodeCursor = cursors.allocateNodeCursor();
        this.propertyCursor = cursors.allocatePropertyCursor();
        this.relationshipCursor = cursors.allocateRelationshipScanCursor();
    }

    @Override
    public long nodeCreate()
    {
        ktx.assertOpen();
        long nodeId = statement.reserveNode();
        ktx.txState().nodeDoCreate( nodeId );
        return nodeId;
    }

    @Override
    public boolean nodeDelete( long node ) throws AutoIndexingKernelException
    {
        ktx.assertOpen();

        if ( ktx.hasTxStateWithChanges() )
        {
            if ( ktx.txState().nodeIsAddedInThisTx( node ) )
            {
                autoIndexing.nodes().entityRemoved( this, node );
                ktx.txState().nodeDoDelete( node );
                return true;
            }
            if ( ktx.txState().nodeIsDeletedInThisTx( node ) )
            {
                // already deleted
                return false;
            }
        }

        ktx.statementLocks().optimistic().acquireExclusive( ktx.lockTracer(), ResourceTypes.NODE, node );
        if ( allStoreHolder.nodeExistsInStore( node ) )
        {
            autoIndexing.nodes().entityRemoved( this, node );
            ktx.txState().nodeDoDelete( node );
            return true;
        }

        // tried to delete node that does not exist
        return false;
    }

    @Override
    public long relationshipCreate( long sourceNode, int relationshipType, long targetNode )
            throws EntityNotFoundException
    {
        ktx.assertOpen();

        sharedRelationshipTypeLock( relationshipType );
        lockRelationshipNodes( sourceNode, targetNode );

        assertNodeExists( sourceNode );
        assertNodeExists( targetNode );

        long id = statement.reserveRelationship();
        ktx.txState().relationshipDoCreate( id, relationshipType, sourceNode, targetNode );
        return id;
    }

    @Override
    public boolean relationshipDelete( long relationship ) throws AutoIndexingKernelException
    {
        ktx.assertOpen();

        allStoreHolder.singleRelationship( relationship, relationshipCursor ); // tx-state aware

        if ( relationshipCursor.next() )
        {
            lockRelationshipNodes( relationshipCursor.sourceNodeReference(), relationshipCursor.targetNodeReference() );
            acquireExclusiveRelationshipLock( relationship );
            if ( !allStoreHolder.relationshipExists( relationship ) )
            {
                return false;
            }

            ktx.assertOpen();

            autoIndexing.relationships().entityRemoved( this, relationship );

            TransactionState txState = ktx.txState();
            if ( txState.relationshipIsAddedInThisTx( relationship ) )
            {
                txState.relationshipDoDeleteAddedInThisTx( relationship );
            }
            else
            {
                txState.relationshipDoDelete( relationship, relationshipCursor.getType(),
                        relationshipCursor.sourceNodeReference(), relationshipCursor.targetNodeReference() );
            }
            return true;
        }

        // tried to delete relationship that does not exist
        return false;
    }

    @Override
    public boolean nodeAddLabel( long node, int nodeLabel )
            throws EntityNotFoundException, ConstraintValidationException
    {
        acquireSharedLabelLock( nodeLabel );
        acquireExclusiveNodeLock( node );

        ktx.assertOpen();
        singleNode( node );

        if ( nodeCursor.labels().contains( nodeLabel ) )
        {
            //label already there, nothing to do
            return false;
        }

        //Check so that we are not breaking uniqueness constraints
        //We do this by checking if there is an existing node in the index that
        //with the same label and property combination.
        Iterator<ConstraintDescriptor> constraints = allStoreHolder.constraintsGetForLabel( nodeLabel );
        while ( constraints.hasNext() )
        {
            ConstraintDescriptor constraint = constraints.next();
            if ( constraint.enforcesUniqueness() )
            {
                IndexBackedConstraintDescriptor uniqueConstraint = (IndexBackedConstraintDescriptor) constraint;
                IndexQuery.ExactPredicate[] propertyValues = getAllPropertyValues( uniqueConstraint.schema(),
                        StatementConstants.NO_SUCH_PROPERTY_KEY, Values.NO_VALUE );
                if ( propertyValues != null )
                {
                    validateNoExistingNodeWithExactValues( uniqueConstraint, propertyValues, node );
                }
            }
        }

        //node is there and doesn't already have the label, let's add
        ktx.txState().nodeDoAddLabel( nodeLabel, node );
        updater.onLabelChange( nodeLabel, nodeCursor, propertyCursor, ADDED_LABEL );
        return true;
    }

    private void singleNode( long node ) throws EntityNotFoundException
    {
        allStoreHolder.singleNode( node, nodeCursor );
        if ( !nodeCursor.next() )
        {
            throw new EntityNotFoundException( EntityType.NODE, node );
        }
    }

    private void singleRelationship( long relationship ) throws EntityNotFoundException
    {
        allStoreHolder.singleRelationship( relationship, relationshipCursor );
        if ( !relationshipCursor.next() )
        {
            throw new EntityNotFoundException( EntityType.RELATIONSHIP, relationship );
        }
    }

    /**
     * Fetch the property values for all properties in schema for a given node. Return these as an exact predicate
     * array.
     */
    private IndexQuery.ExactPredicate[] getAllPropertyValues( SchemaDescriptor schema, int changedPropertyKeyId,
            Value changedValue )
    {
        int[] schemaPropertyIds = schema.getPropertyIds();
        IndexQuery.ExactPredicate[] values = new IndexQuery.ExactPredicate[schemaPropertyIds.length];

        int nMatched = 0;
        nodeCursor.properties( propertyCursor );
        while ( propertyCursor.next() )
        {
            int nodePropertyId = propertyCursor.propertyKey();
            int k = ArrayUtils.indexOf( schemaPropertyIds, nodePropertyId );
            if ( k >= 0 )
            {
                if ( nodePropertyId != StatementConstants.NO_SUCH_PROPERTY_KEY )
                {
                    values[k] = IndexQuery.exact( nodePropertyId, propertyCursor.propertyValue() );
                }
                nMatched++;
            }
        }

        //This is true if we are adding a property
        if ( changedPropertyKeyId != NO_SUCH_PROPERTY_KEY )
        {
            int k = ArrayUtils.indexOf( schemaPropertyIds, changedPropertyKeyId );
            if ( k >= 0 )
            {
                values[k] = IndexQuery.exact( changedPropertyKeyId, changedValue );
                nMatched++;
            }
        }

        if ( nMatched < values.length )
        {
            return null;
        }
        return values;
    }

    /**
     * Check so that there is not an existing node with the exact match of label and property
     */
    private void validateNoExistingNodeWithExactValues( IndexBackedConstraintDescriptor constraint,
            IndexQuery.ExactPredicate[] propertyValues, long modifiedNode
    ) throws UniquePropertyValueValidationException, UnableToValidateConstraintException
    {
        try ( DefaultNodeValueIndexCursor valueCursor = cursors.allocateNodeValueIndexCursor() )
        {
            SchemaIndexDescriptor schemaIndexDescriptor = constraint.ownedIndexDescriptor();
            assertIndexOnline( schemaIndexDescriptor );
            int labelId = schemaIndexDescriptor.schema().getLabelId();

            //Take a big fat lock, and check for existing node in index
            ktx.statementLocks().optimistic().acquireExclusive(
                    ktx.lockTracer(), INDEX_ENTRY,
                    indexEntryResourceId( labelId, propertyValues )
            );

            allStoreHolder.nodeIndexSeek( allStoreHolder.indexGetCapability( schemaIndexDescriptor ), valueCursor,
                    IndexOrder.NONE, propertyValues );
            if ( valueCursor.next() && valueCursor.nodeReference() != modifiedNode )
            {
                throw new UniquePropertyValueValidationException( constraint, VALIDATION,
                        new IndexEntryConflictException( valueCursor.nodeReference(), NO_SUCH_NODE,
                                IndexQuery.asValueTuple( propertyValues ) ) );
            }
        }
        catch ( IndexNotFoundKernelException | IndexBrokenKernelException | IndexNotApplicableKernelException e )
        {
            throw new UnableToValidateConstraintException( constraint, e );
        }
    }

    private void assertIndexOnline( SchemaIndexDescriptor descriptor )
            throws IndexNotFoundKernelException, IndexBrokenKernelException
    {
        switch ( allStoreHolder.indexGetState( descriptor ) )
        {
        case ONLINE:
            return;
        default:
            throw new IndexBrokenKernelException( allStoreHolder.indexGetFailure( descriptor ) );
        }
    }

    @Override
    public boolean nodeRemoveLabel( long node, int nodeLabel ) throws EntityNotFoundException
    {
        acquireExclusiveNodeLock( node );
        ktx.assertOpen();

        singleNode( node );

        if ( !nodeCursor.labels().contains( nodeLabel ) )
        {
            //the label wasn't there, nothing to do
            return false;
        }

        ktx.txState().nodeDoRemoveLabel( nodeLabel, node );
        updater.onLabelChange( nodeLabel, nodeCursor, propertyCursor, REMOVED_LABEL );
        return true;
    }

    @Override
    public Value nodeSetProperty( long node, int propertyKey, Value value )
            throws EntityNotFoundException, ConstraintValidationException, AutoIndexingKernelException

    {
        acquireExclusiveNodeLock( node );
        ktx.assertOpen();

        singleNode( node );
        ktx.statementLocks().optimistic().acquireShared( ktx.lockTracer(), ResourceTypes.LABEL,
                nodeCursor.labels().all() );
        Iterator<ConstraintDescriptor> constraints = Iterators.filter( hasProperty( propertyKey ),
                allStoreHolder.constraintsGetAll() );
        Iterator<IndexBackedConstraintDescriptor> uniquenessConstraints =
                new CastingIterator<>( constraints, IndexBackedConstraintDescriptor.class );

        NodeSchemaMatcher.onMatchingSchema( uniquenessConstraints, nodeCursor, propertyCursor, propertyKey,
                ( constraint, propertyIds ) ->
                {
                    if ( propertyIds.contains( propertyKey ) )
                    {
                        Value previousValue = readNodeProperty( propertyKey );
                        if ( value.equals( previousValue ) )
                        {
                            // since we are changing to the same value, there is no need to check
                            return;
                        }
                    }
                    validateNoExistingNodeWithExactValues( constraint,
                            getAllPropertyValues( constraint.schema(), propertyKey, value ), node );
                } );

        Value existingValue = readNodeProperty( propertyKey );

        if ( existingValue == NO_VALUE )
        {
            //no existing value, we just add it
            autoIndexing.nodes().propertyAdded( this, node, propertyKey, value );
            ktx.txState().nodeDoAddProperty( node, propertyKey, value );
            updater.onPropertyAdd( nodeCursor, propertyCursor, propertyKey, value );
            return NO_VALUE;
        }
        else
        {
            if ( propertyHasChanged( value, existingValue ) )
            {
                //the value has changed to a new value
                autoIndexing.nodes().propertyChanged( this, node, propertyKey, existingValue, value );
                ktx.txState().nodeDoChangeProperty( node, propertyKey, existingValue, value );
                updater.onPropertyChange( nodeCursor, propertyCursor, propertyKey, existingValue, value );
            }
            return existingValue;
        }
    }

    @Override
    public Value nodeRemoveProperty( long node, int propertyKey )
            throws EntityNotFoundException, AutoIndexingKernelException
    {
        acquireExclusiveNodeLock( node );
        ktx.assertOpen();
        singleNode( node );
        Value existingValue = readNodeProperty( propertyKey );

        if ( existingValue != NO_VALUE )
        {
            autoIndexing.nodes().propertyRemoved( this, node, propertyKey );
            ktx.txState().nodeDoRemoveProperty( node, propertyKey, existingValue );
            updater.onPropertyRemove( nodeCursor, propertyCursor, propertyKey, existingValue );
        }

        return existingValue;
    }

    @Override
    public Value relationshipSetProperty( long relationship, int propertyKey, Value value )
            throws EntityNotFoundException, AutoIndexingKernelException
    {
        acquireExclusiveRelationshipLock( relationship );
        ktx.assertOpen();
        singleRelationship( relationship );
        Value existingValue = readRelationshipProperty( propertyKey );
        if ( existingValue == NO_VALUE )
        {
            autoIndexing.relationships().propertyAdded( this, relationship, propertyKey, value );
            ktx.txState().relationshipDoReplaceProperty( relationship, propertyKey, NO_VALUE, value );
            return NO_VALUE;
        }
        else
        {
            if ( propertyHasChanged( existingValue, value ) )
            {
                autoIndexing.relationships().propertyChanged( this, relationship, propertyKey, existingValue, value );

                ktx.txState().relationshipDoReplaceProperty( relationship, propertyKey, existingValue, value );
            }

            return existingValue;
        }
    }

    @Override
    public Value relationshipRemoveProperty( long relationship, int propertyKey )
            throws EntityNotFoundException, AutoIndexingKernelException
    {
        acquireExclusiveRelationshipLock( relationship );
        ktx.assertOpen();
        singleRelationship( relationship );
        Value existingValue = readRelationshipProperty( propertyKey );

        if ( existingValue != NO_VALUE )
        {
            autoIndexing.relationships().propertyRemoved( this, relationship, propertyKey );
            ktx.txState().relationshipDoRemoveProperty( relationship, propertyKey, existingValue );
        }

        return existingValue;
    }

    @Override
    public Value graphSetProperty( int propertyKey, Value value )
    {
        ktx.statementLocks().optimistic()
                .acquireExclusive( ktx.lockTracer(), ResourceTypes.GRAPH_PROPS, ResourceTypes.graphPropertyResource() );
        ktx.assertOpen();

        Value existingValue = readGraphProperty( propertyKey );
        if ( !existingValue.equals( value ) )
        {
            ktx.txState().graphDoReplaceProperty( propertyKey, existingValue, value );
        }
        return existingValue;
    }

    @Override
    public Value graphRemoveProperty( int propertyKey )
    {
        ktx.statementLocks().optimistic()
                .acquireExclusive( ktx.lockTracer(), ResourceTypes.GRAPH_PROPS, ResourceTypes.graphPropertyResource() );
        ktx.assertOpen();
        Value existingValue = readGraphProperty( propertyKey );
        if ( existingValue != Values.NO_VALUE )
        {
            ktx.txState().graphDoRemoveProperty( propertyKey, existingValue );
        }
        return existingValue;
    }

    @Override
    public void nodeAddToExplicitIndex( String indexName, long node, String key, Object value )
            throws ExplicitIndexNotFoundKernelException
    {
        ktx.assertOpen();
        ktx.explicitIndexTxState().nodeChanges( indexName ).addNode( node, key, value );
    }

    @Override
    public void nodeRemoveFromExplicitIndex( String indexName, long node ) throws ExplicitIndexNotFoundKernelException
    {
        ktx.assertOpen();
        ktx.explicitIndexTxState().nodeChanges( indexName ).remove( node );
    }

    @Override
    public void nodeRemoveFromExplicitIndex( String indexName, long node, String key, Object value )
            throws ExplicitIndexNotFoundKernelException
    {
        ktx.assertOpen();
        ktx.explicitIndexTxState().nodeChanges( indexName ).remove( node, key, value );
    }

    @Override
    public void nodeRemoveFromExplicitIndex( String indexName, long node, String key )
            throws ExplicitIndexNotFoundKernelException
    {
        ktx.assertOpen();
        ktx.explicitIndexTxState().nodeChanges( indexName ).remove( node, key );
    }

    @Override
    public void nodeExplicitIndexCreate( String indexName, Map<String,String> customConfig )
    {
        ktx.assertOpen();
        ktx.explicitIndexTxState().createIndex( IndexEntityType.Node, indexName, customConfig );
    }

    @Override
    public void nodeExplicitIndexCreateLazily( String indexName, Map<String,String> customConfig )
    {
        ktx.assertOpen();
        allStoreHolder.getOrCreateNodeIndexConfig( indexName, customConfig );
    }

    @Override
    public void relationshipAddToExplicitIndex( String indexName, long relationship, String key, Object value )
            throws KernelException
    {
        ktx.assertOpen();
        allStoreHolder.singleRelationship( relationship, relationshipCursor );
        if ( relationshipCursor.next() )
        {
            ktx.explicitIndexTxState().relationshipChanges( indexName ).addRelationship( relationship, key, value,
                    relationshipCursor.sourceNodeReference(), relationshipCursor.targetNodeReference() );
        }
        else
        {
            throw new EntityNotFoundException( EntityType.RELATIONSHIP, relationship );
        }
    }

    @Override
    public void relationshipRemoveFromExplicitIndex( String indexName, long relationship, String key, Object value )
            throws ExplicitIndexNotFoundKernelException
    {
        ktx.assertOpen();
        ktx.explicitIndexTxState().relationshipChanges( indexName ).remove( relationship, key, value );
    }

    @Override
    public void relationshipRemoveFromExplicitIndex( String indexName, long relationship, String key )
            throws ExplicitIndexNotFoundKernelException
    {
        ktx.explicitIndexTxState().relationshipChanges( indexName ).remove( relationship, key );

    }

    @Override
    public void relationshipRemoveFromExplicitIndex( String indexName, long relationship )
            throws ExplicitIndexNotFoundKernelException
    {
        ktx.explicitIndexTxState().relationshipChanges( indexName ).remove( relationship );
    }

    @Override
    public void relationshipExplicitIndexCreate( String indexName, Map<String,String> customConfig )
    {
        ktx.assertOpen();
        ktx.explicitIndexTxState().createIndex( IndexEntityType.Relationship, indexName, customConfig );
    }

    @Override
    public void relationshipExplicitIndexCreateLazily( String indexName, Map<String,String> customConfig )
    {
        ktx.assertOpen();
        allStoreHolder.getOrCreateRelationshipIndexConfig( indexName, customConfig );
    }

    private Value readNodeProperty( int propertyKey )
    {
        nodeCursor.properties( propertyCursor );

        //Find out if the property had a value
        Value existingValue = NO_VALUE;
        while ( propertyCursor.next() )
        {
            if ( propertyCursor.propertyKey() == propertyKey )
            {
                existingValue = propertyCursor.propertyValue();
                break;
            }
        }
        return existingValue;
    }

    private Value readRelationshipProperty( int propertyKey )
    {
        relationshipCursor.properties( propertyCursor );

        //Find out if the property had a value
        Value existingValue = NO_VALUE;
        while ( propertyCursor.next() )
        {
            if ( propertyCursor.propertyKey() == propertyKey )
            {
                existingValue = propertyCursor.propertyValue();
                break;
            }
        }
        return existingValue;
    }

    private Value readGraphProperty( int propertyKey )
    {
        allStoreHolder.graphProperties( propertyCursor );

        //Find out if the property had a value
        Value existingValue = NO_VALUE;
        while ( propertyCursor.next() )
        {
            if ( propertyCursor.propertyKey() == propertyKey )
            {
                existingValue = propertyCursor.propertyValue();
                break;
            }
        }
        return existingValue;
    }

    public CursorFactory cursors()
    {
        return cursors;
    }

    public Procedures procedures()
    {
        return allStoreHolder;
    }

    public void release()
    {
        if ( nodeCursor != null )
        {
            nodeCursor.close();
            nodeCursor = null;
        }
        if ( propertyCursor != null )
        {
            propertyCursor.close();
            propertyCursor = null;
        }
        if ( relationshipCursor != null )
        {
            relationshipCursor.close();
            relationshipCursor = null;
        }

        cursors.assertClosed();
        cursors.release();
    }

    public Token token()
    {
        return token;
    }

    public ExplicitIndexRead indexRead()
    {
        return allStoreHolder;
    }

    public SchemaRead schemaRead()
    {
        return allStoreHolder;
    }

    public Read dataRead()
    {
        return allStoreHolder;
    }

    public DefaultNodeCursor nodeCursor()
    {
        return nodeCursor;
    }

    public DefaultRelationshipScanCursor relationshipCursor()
    {
        return relationshipCursor;
    }

    public DefaultPropertyCursor propertyCursor()
    {
        return propertyCursor;
    }

    @Override
    public CapableIndexReference indexCreate( LabelSchemaDescriptor descriptor ) throws SchemaKernelException
    {
        acquireExclusiveLabelLock( descriptor.getLabelId() );
        ktx.assertOpen();
        assertValidDescriptor( descriptor, SchemaKernelException.OperationContext.INDEX_CREATION );
        assertIndexDoesNotExist( SchemaKernelException.OperationContext.INDEX_CREATION, descriptor );

        SchemaIndexDescriptor indexDescriptor = SchemaIndexDescriptorFactory.forSchema( descriptor );
        ktx.txState().indexRuleDoAdd( indexDescriptor );
        return fromDescriptor( indexDescriptor );
    }

    @Override
    public void indexDrop( CapableIndexReference index ) throws SchemaKernelException
    {
        assertValidIndex( index );

        acquireExclusiveLabelLock( index.label() );
        ktx.assertOpen();
        org.neo4j.kernel.api.schema.LabelSchemaDescriptor descriptor = labelDescriptor( index );
        try
        {
            SchemaIndexDescriptor existingIndex = allStoreHolder.indexGetForSchema( descriptor );

            if ( existingIndex == null )
            {
                throw new NoSuchIndexException( descriptor );
            }

            if ( existingIndex.type() == UNIQUE )
            {
                if ( allStoreHolder.indexGetOwningUniquenessConstraintId( existingIndex ) != null )
                {
                    throw new IndexBelongsToConstraintException( descriptor );
                }
            }
        }
        catch ( IndexBelongsToConstraintException | NoSuchIndexException e )
        {
            throw new DropIndexFailureException( descriptor, e );
        }
        ktx.txState().indexDoDrop( allStoreHolder.indexDescriptor( index ) );
    }

    @Override
    public ConstraintDescriptor uniquePropertyConstraintCreate( LabelSchemaDescriptor descriptor )
            throws SchemaKernelException
    {
        //Lock
        acquireExclusiveLabelLock( descriptor.getLabelId() );
        ktx.assertOpen();

        //Check data integrity
        assertValidDescriptor( descriptor, SchemaKernelException.OperationContext.CONSTRAINT_CREATION );
        UniquenessConstraintDescriptor constraint = ConstraintDescriptorFactory.uniqueForSchema( descriptor );
        assertConstraintDoesNotExist( constraint );
        // It is not allowed to create uniqueness constraints on indexed label/property pairs
        assertIndexDoesNotExist( SchemaKernelException.OperationContext.CONSTRAINT_CREATION, descriptor );

        // Create constraints
        indexBackedConstraintCreate( constraint );
        return constraint;
    }

    @Override
    public ConstraintDescriptor nodeKeyConstraintCreate( LabelSchemaDescriptor descriptor ) throws SchemaKernelException
    {
        //Lock
        acquireExclusiveLabelLock( descriptor.getLabelId() );
        ktx.assertOpen();

        //Check data integrity
        assertValidDescriptor( descriptor, SchemaKernelException.OperationContext.CONSTRAINT_CREATION );
        ConstraintDescriptor constraint = ConstraintDescriptorFactory.existsForSchema( descriptor );
        assertConstraintDoesNotExist( constraint );

        //enforce constraints
        try ( NodeLabelIndexCursor nodes = cursors.allocateNodeLabelIndexCursor() )
        {
            allStoreHolder.nodeLabelScan( descriptor.getLabelId(), nodes );
            constraintSemantics.validateNodeKeyConstraint( nodes, nodeCursor, propertyCursor, descriptor );
        }

        //create constraint
        ktx.txState().constraintDoAdd( constraint );
        return constraint;

    }

    @Override
    public ConstraintDescriptor nodePropertyExistenceConstraintCreate( LabelSchemaDescriptor descriptor )
            throws SchemaKernelException
    {
        //Lock
        acquireExclusiveLabelLock( descriptor.getLabelId() );
        ktx.assertOpen();

        //verify data integrity
        assertValidDescriptor( descriptor, SchemaKernelException.OperationContext.CONSTRAINT_CREATION );
        ConstraintDescriptor constraint = ConstraintDescriptorFactory.existsForSchema( descriptor );
        assertConstraintDoesNotExist( constraint );

        //enforce constraints
        try ( NodeLabelIndexCursor nodes = cursors.allocateNodeLabelIndexCursor() )
        {
            allStoreHolder.nodeLabelScan( descriptor.getLabelId(), nodes );
            constraintSemantics
                    .validateNodePropertyExistenceConstraint( nodes, nodeCursor, propertyCursor, descriptor );
        }

        //create constraint
        ktx.txState().constraintDoAdd( constraint );
        return constraint;
    }

    @Override
    public ConstraintDescriptor relationshipPropertyExistenceConstraintCreate( RelationTypeSchemaDescriptor descriptor )
            throws SchemaKernelException
    {
        //Lock
        acquireExclusiveRelationshipLock( descriptor.getRelTypeId() );
        ktx.assertOpen();

        //verify data integrity
        assertValidDescriptor( descriptor, SchemaKernelException.OperationContext.CONSTRAINT_CREATION );
        ConstraintDescriptor constraint = ConstraintDescriptorFactory.existsForSchema( descriptor );
        assertConstraintDoesNotExist( constraint );

        //enforce constraints
        allStoreHolder.relationshipLabelScan( descriptor.getRelTypeId(), relationshipCursor );
        constraintSemantics
                .validateRelationshipPropertyExistenceConstraint( relationshipCursor, propertyCursor, descriptor );

        //Create
        ktx.txState().constraintDoAdd( constraint );
        return constraint;

    }

    @Override
    public void constraintDrop( ConstraintDescriptor descriptor ) throws SchemaKernelException
    {
        //Lock
        SchemaDescriptor schema = descriptor.schema();
        exclusiveOptimisticLock( schema.keyType(), schema.keyId() );
        ktx.assertOpen();

        //verify data integrity
        try
        {
            assertConstraintExists( descriptor );
        }
        catch ( NoSuchConstraintException e )
        {
            throw new DropConstraintFailureException( descriptor, e );
        }

        //Drop it like it's hot
        ktx.txState().constraintDoDrop( descriptor );
    }

    private void assertIndexDoesNotExist( SchemaKernelException.OperationContext context,
            LabelSchemaDescriptor descriptor )
            throws AlreadyIndexedException, AlreadyConstrainedException
    {
        SchemaIndexDescriptor existingIndex = allStoreHolder.indexGetForSchema( descriptor );
        if ( existingIndex != null )
        {
            // OK so we found a matching constraint index. We check whether or not it has an owner
            // because this may have been a left-over constraint index from a previously failed
            // constraint creation, due to crash or similar, hence the missing owner.
            if ( existingIndex.type() == UNIQUE )
            {
                if ( context != CONSTRAINT_CREATION || constraintIndexHasOwner( existingIndex ) )
                {
                    throw new AlreadyConstrainedException( ConstraintDescriptorFactory.uniqueForSchema( descriptor ),
                            context, new SilentTokenNameLookup( token ) );
                }
            }
            else
            {
                throw new AlreadyIndexedException( descriptor, context );
            }
        }
    }

    private void exclusiveOptimisticLock( ResourceType resource, long resourceId )
    {
        ktx.statementLocks().optimistic().acquireExclusive( ktx.lockTracer(), resource, resourceId );
    }

    private void acquireExclusiveNodeLock( long node )
    {
        if ( !ktx.hasTxStateWithChanges() || !ktx.txState().nodeIsAddedInThisTx( node ) )
        {
            ktx.statementLocks().optimistic().acquireExclusive( ktx.lockTracer(), ResourceTypes.NODE, node );
        }
    }

    private void acquireExclusiveRelationshipLock( long relationshipId )
    {
        if ( !ktx.hasTxStateWithChanges() || !ktx.txState().relationshipIsAddedInThisTx( relationshipId ) )
        {
            ktx.statementLocks().optimistic().acquireExclusive( ktx.lockTracer(), ResourceTypes.RELATIONSHIP, relationshipId );
        }
    }

    private void acquireSharedLabelLock( int labelId )
    {
        ktx.statementLocks().optimistic().acquireShared( ktx.lockTracer(), ResourceTypes.LABEL, labelId );
    }

    private void acquireExclusiveLabelLock( int labelId )
    {
        ktx.statementLocks().optimistic().acquireExclusive( ktx.lockTracer(), ResourceTypes.LABEL, labelId );
    }

    private void sharedRelationshipTypeLock( long typeId )
    {
        ktx.statementLocks().optimistic().acquireShared( ktx.lockTracer(), ResourceTypes.RELATIONSHIP_TYPE, typeId );
    }

    private void lockRelationshipNodes( long startNodeId, long endNodeId )
    {
        // Order the locks to lower the risk of deadlocks with other threads creating/deleting rels concurrently
        acquireExclusiveNodeLock( min( startNodeId, endNodeId ) );
        if ( startNodeId != endNodeId )
        {
            acquireExclusiveNodeLock( max( startNodeId, endNodeId ) );
        }
    }

    private boolean propertyHasChanged( Value lhs, Value rhs )
    {
        //It is not enough to check equality here since by our equality semantics `int == tofloat(int)` is `true`
        //so by only checking for equality users cannot change type of property without also "changing" the value.
        //Hence the extra type check here.
        return lhs.getClass() != rhs.getClass() || !lhs.equals( rhs );
    }

    private void assertNodeExists( long sourceNode ) throws EntityNotFoundException
    {
        if ( !allStoreHolder.nodeExists( sourceNode ) )
        {
            throw new EntityNotFoundException( EntityType.NODE, sourceNode );
        }
    }

    private boolean constraintIndexHasOwner( SchemaIndexDescriptor descriptor )
    {
        return allStoreHolder.indexGetOwningUniquenessConstraintId( descriptor ) != null;
    }

    private void assertConstraintDoesNotExist( ConstraintDescriptor constraint )
            throws AlreadyConstrainedException
    {
        if ( allStoreHolder.constraintExists( constraint ) )
        {
            throw new AlreadyConstrainedException( constraint,
                    SchemaKernelException.OperationContext.CONSTRAINT_CREATION,
                    new SilentTokenNameLookup( token ) );
        }
    }

    public Locks locks()
    {
        return allStoreHolder;
    }

    private void assertConstraintExists( ConstraintDescriptor constraint )
            throws NoSuchConstraintException
    {
        if ( !allStoreHolder.constraintExists( constraint ) )
        {
            throw new NoSuchConstraintException( constraint );
        }
    }

    private void assertValidDescriptor( SchemaDescriptor descriptor, SchemaKernelException.OperationContext context )
            throws RepeatedPropertyInCompositeSchemaException
    {
        int numUnique = Arrays.stream( descriptor.getPropertyIds() ).distinct().toArray().length;
        if ( numUnique != descriptor.getPropertyIds().length )
        {
            throw new RepeatedPropertyInCompositeSchemaException( descriptor, context );
        }
    }

    private org.neo4j.kernel.api.schema.LabelSchemaDescriptor labelDescriptor( IndexReference index )
    {
        return new org.neo4j.kernel.api.schema.LabelSchemaDescriptor( index.label(), index.properties() );
    }

    private void indexBackedConstraintCreate( IndexBackedConstraintDescriptor constraint )
            throws CreateConstraintFailureException
    {
        LabelSchemaDescriptor descriptor = constraint.schema();
        try
        {
            if ( ktx.hasTxStateWithChanges() &&
                 ktx.txState().indexDoUnRemove( constraint.ownedIndexDescriptor() ) ) // ..., DROP, *CREATE*
            { // creation is undoing a drop
                if ( !ktx.txState().constraintDoUnRemove( constraint ) ) // CREATE, ..., DROP, *CREATE*
                { // ... the drop we are undoing did itself undo a prior create...
                    ktx.txState().constraintDoAdd(
                            constraint, ktx.txState().indexCreatedForConstraint( constraint ) );
                }
            }
            else // *CREATE*
            { // create from scratch
                Iterator<ConstraintDescriptor> it = allStoreHolder.constraintsGetForSchema( descriptor );
                while ( it.hasNext() )
                {
                    if ( it.next().equals( constraint ) )
                    {
                        return;
                    }
                }
                long indexId = constraintIndexCreator.createUniquenessConstraintIndex( ktx, descriptor );
                if ( !allStoreHolder.constraintExists( constraint ) )
                {
                    // This looks weird, but since we release the label lock while awaiting population of the index
                    // backing this constraint there can be someone else getting ahead of us, creating this exact
                    // constraint
                    // before we do, so now getting out here under the lock we must check again and if it exists
                    // we must at this point consider this an idempotent operation because we verified earlier
                    // that it didn't exist and went on to create it.
                    ktx.txState().constraintDoAdd( constraint, indexId );
                }
            }
        }
        catch ( UniquePropertyValueValidationException | TransactionFailureException | AlreadyConstrainedException e )
        {
            throw new CreateConstraintFailureException( constraint, e );
        }
    }

    private void assertValidIndex( CapableIndexReference index ) throws NoSuchIndexException
    {
        if ( index == CapableIndexReference.NO_INDEX )
        {
            throw new NoSuchIndexException( new org.neo4j.kernel.api.schema.LabelSchemaDescriptor( index.label(), index.properties() ) );
        }
    }
}
