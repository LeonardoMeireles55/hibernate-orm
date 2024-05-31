/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.sql.results.graph.entity.internal;

import java.util.function.BiConsumer;

import org.hibernate.FetchNotFoundException;
import org.hibernate.bytecode.enhance.spi.LazyPropertyInitializer;
import org.hibernate.engine.spi.EntityHolder;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.EntityUniqueKey;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.internal.log.LoggingHelper;
import org.hibernate.metamodel.mapping.ModelPart;
import org.hibernate.metamodel.mapping.internal.ToOneAttributeMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.results.graph.DomainResultAssembler;
import org.hibernate.sql.results.graph.FetchParentAccess;
import org.hibernate.sql.results.graph.Initializer;
import org.hibernate.sql.results.graph.InitializerParent;
import org.hibernate.sql.results.graph.basic.BasicResultAssembler;
import org.hibernate.sql.results.graph.entity.EntityInitializer;
import org.hibernate.sql.results.graph.internal.AbstractInitializer;
import org.hibernate.type.Type;

import org.checkerframework.checker.nullness.qual.Nullable;

import static org.hibernate.sql.results.graph.entity.internal.EntityInitializerImpl.determineConcreteEntityDescriptor;

/**
 * @author Andrea Boriero
 * @author Steve Ebersole
 */
public class EntityDelayedFetchInitializer extends AbstractInitializer implements EntityInitializer {

	private final InitializerParent parent;
	private final NavigablePath navigablePath;
	private final boolean isPartOfKey;
	private final ToOneAttributeMapping referencedModelPart;
	private final boolean selectByUniqueKey;
	private final DomainResultAssembler<?> identifierAssembler;
	private final BasicResultAssembler<?> discriminatorAssembler;

	// per-row state
	private Object entityInstance;
	private Object identifier;

	public EntityDelayedFetchInitializer(
			InitializerParent parent,
			NavigablePath fetchedNavigable,
			ToOneAttributeMapping referencedModelPart,
			boolean selectByUniqueKey,
			DomainResultAssembler<?> identifierAssembler,
			BasicResultAssembler<?> discriminatorAssembler) {
		// associations marked with `@NotFound` are ALWAYS eagerly fetched, unless we're resolving the concrete type
		assert !referencedModelPart.hasNotFoundAction() || referencedModelPart.getEntityMappingType().isConcreteProxy();

		this.parent = parent;
		this.navigablePath = fetchedNavigable;
		this.isPartOfKey = Initializer.isPartOfKey( fetchedNavigable, parent );
		this.referencedModelPart = referencedModelPart;
		this.selectByUniqueKey = selectByUniqueKey;
		this.identifierAssembler = identifierAssembler;
		this.discriminatorAssembler = discriminatorAssembler;
	}

	@Override
	public NavigablePath getNavigablePath() {
		return navigablePath;
	}

	@Override
	public ModelPart getInitializedPart() {
		return referencedModelPart;
	}

	@Override
	public void resolveInstance() {
		if ( state != State.KEY_RESOLVED ) {
			return;
		}

		state = State.RESOLVED;

		identifier = identifierAssembler.assemble( rowProcessingState );

		if ( identifier == null ) {
			entityInstance = null;
			state = State.MISSING;
		}
		else {
			final SharedSessionContractImplementor session = rowProcessingState.getSession();

			final EntityPersister entityPersister = referencedModelPart.getEntityMappingType().getEntityPersister();
			final EntityPersister concreteDescriptor;
			if ( discriminatorAssembler != null ) {
				concreteDescriptor = determineConcreteEntityDescriptor(
						rowProcessingState,
						discriminatorAssembler,
						entityPersister
				);
				if ( concreteDescriptor == null ) {
					// If we find no discriminator it means there's no entity in the target table
					if ( !referencedModelPart.isOptional() ) {
						throw new FetchNotFoundException( entityPersister.getEntityName(), identifier );
					}
					entityInstance = null;
					state = State.MISSING;
					return;
				}
			}
			else {
				concreteDescriptor = entityPersister;
			}

			final PersistenceContext persistenceContext = session.getPersistenceContextInternal();
			if ( selectByUniqueKey ) {
				final String uniqueKeyPropertyName = referencedModelPart.getReferencedPropertyName();
				final Type uniqueKeyPropertyType = ( referencedModelPart.getReferencedPropertyName() == null ) ?
						concreteDescriptor.getIdentifierType() :
						session.getFactory()
								.getReferencedPropertyType(
										concreteDescriptor.getEntityName(),
										uniqueKeyPropertyName
								);

				final EntityUniqueKey euk = new EntityUniqueKey(
						concreteDescriptor.getEntityName(),
						uniqueKeyPropertyName,
						identifier,
						uniqueKeyPropertyType,
						session.getFactory()
				);
				entityInstance = persistenceContext.getEntity( euk );
				if ( entityInstance == null ) {
					// For unique-key mappings, we always use bytecode-laziness if possible,
					// because we can't generate a proxy based on the unique key yet
					if ( referencedModelPart.isLazy() ) {
						entityInstance = LazyPropertyInitializer.UNFETCHED_PROPERTY;
					}
					else {
						entityInstance = concreteDescriptor.loadByUniqueKey(
								uniqueKeyPropertyName,
								identifier,
								session
						);

						// If the entity was not in the Persistence Context, but was found now,
						// add it to the Persistence Context
						if ( entityInstance != null ) {
							persistenceContext.addEntity( euk, entityInstance );
						}
					}
				}
				if ( entityInstance != null ) {
					entityInstance = persistenceContext.proxyFor( entityInstance );
				}
			}
			else {
				final EntityKey entityKey = new EntityKey( identifier, concreteDescriptor );
				final EntityHolder holder = persistenceContext.getEntityHolder( entityKey );
				if ( holder != null && holder.getEntity() != null ) {
					entityInstance = persistenceContext.proxyFor( holder, concreteDescriptor );
				}
				// For primary key based mappings we only use bytecode-laziness if the attribute is optional,
				// because the non-optionality implies that it is safe to have a proxy
				else if ( referencedModelPart.isOptional() && referencedModelPart.isLazy() ) {
					entityInstance = LazyPropertyInitializer.UNFETCHED_PROPERTY;
				}
				else {
					entityInstance = session.internalLoad(
							concreteDescriptor.getEntityName(),
							identifier,
							false,
							false
					);

					final LazyInitializer lazyInitializer = HibernateProxy.extractLazyInitializer( entityInstance );
					if ( lazyInitializer != null ) {
						lazyInitializer.setUnwrap( referencedModelPart.isUnwrapProxy() && concreteDescriptor.isInstrumented() );
					}
				}
			}
		}
	}

	@Override
	public void resolveInstance(Object instance) {
		if ( instance == null ) {
			state = State.MISSING;
			identifier = null;
			entityInstance = null;
		}
		else {
			state = State.RESOLVED;
			final SharedSessionContractImplementor session = rowProcessingState.getSession();
			final EntityPersister concreteDescriptor = referencedModelPart.getEntityMappingType().getEntityPersister();
			identifier = concreteDescriptor.getIdentifier( instance, session );
			entityInstance = instance;
			final Initializer initializer = identifierAssembler.getInitializer();
			if ( initializer != null ) {
				initializer.resolveInstance( identifier );
			}
			else if ( !rowProcessingState.isQueryCacheHit() && rowProcessingState.getQueryOptions().isResultCachingEnabled() == Boolean.TRUE ) {
				// Resolve the state of the identifier if result caching is enabled and this is not a query cache hit
				identifierAssembler.resolveState( rowProcessingState );
			}
		}
	}

	@Override
	protected <X> void forEachSubInitializer(BiConsumer<Initializer, X> consumer, X arg) {
		final Initializer initializer = identifierAssembler.getInitializer();
		if ( initializer != null ) {
			consumer.accept( initializer, arg );
		}
	}

	@Override
	public EntityPersister getEntityDescriptor() {
		return referencedModelPart.getEntityMappingType().getEntityPersister();
	}

	@Override
	public Object getEntityInstance() {
		return entityInstance;
	}

	@Override
	public boolean isEntityInitialized() {
		return false;
	}

	@Override
	public FetchParentAccess getFetchParentAccess() {
		return (FetchParentAccess) parent;
	}

	@Override
	public @Nullable InitializerParent getParent() {
		return parent;
	}

	@Override
	public boolean isPartOfKey() {
		return isPartOfKey;
	}

	@Override
	public boolean isResultInitializer() {
		return false;
	}

	@Override
	public EntityPersister getConcreteDescriptor() {
		return getEntityDescriptor();
	}

	@Override
	public String toString() {
		return "EntityDelayedFetchInitializer(" + LoggingHelper.toLoggableString( navigablePath ) + ")";
	}

	//#########################
	// For Hibernate Reactive
	//#########################
	protected void setEntityInstance(Object entityInstance) {
		this.entityInstance = entityInstance;
	}

	protected Object getIdentifier() {
		return identifier;
	}

	protected void setIdentifier(Object identifier) {
		this.identifier = identifier;
	}

	protected boolean isSelectByUniqueKey() {
		return selectByUniqueKey;
	}

	protected DomainResultAssembler<?> getIdentifierAssembler() {
		return identifierAssembler;
	}

	@Override
	public EntityKey getEntityKey() {
		throw new UnsupportedOperationException(
				"This should never happen, because this initializer has not child initializers" );
	}

	@Override
	public Object getParentKey() {
		throw new UnsupportedOperationException(
				"This should never happen, because this initializer has not child initializers" );
	}
}
