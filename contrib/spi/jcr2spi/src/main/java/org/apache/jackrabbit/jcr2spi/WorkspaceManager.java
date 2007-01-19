/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.jcr2spi;

import org.apache.jackrabbit.jcr2spi.nodetype.NodeTypeRegistryImpl;
import org.apache.jackrabbit.jcr2spi.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.jcr2spi.nodetype.NodeTypeStorage;
import org.apache.jackrabbit.jcr2spi.name.NamespaceStorage;
import org.apache.jackrabbit.jcr2spi.name.NamespaceRegistryImpl;
import org.apache.jackrabbit.jcr2spi.state.ItemState;
import org.apache.jackrabbit.jcr2spi.state.NoSuchItemStateException;
import org.apache.jackrabbit.jcr2spi.state.ItemStateException;
import org.apache.jackrabbit.jcr2spi.state.PropertyState;
import org.apache.jackrabbit.jcr2spi.state.ChangeLog;
import org.apache.jackrabbit.jcr2spi.state.UpdatableItemStateManager;
import org.apache.jackrabbit.jcr2spi.state.ItemStateFactory;
import org.apache.jackrabbit.jcr2spi.state.WorkspaceItemStateFactory;
import org.apache.jackrabbit.jcr2spi.state.NodeState;
import org.apache.jackrabbit.jcr2spi.state.ItemStateManager;
import org.apache.jackrabbit.jcr2spi.state.WorkspaceItemStateManager;
import org.apache.jackrabbit.jcr2spi.operation.OperationVisitor;
import org.apache.jackrabbit.jcr2spi.operation.AddNode;
import org.apache.jackrabbit.jcr2spi.operation.AddProperty;
import org.apache.jackrabbit.jcr2spi.operation.Clone;
import org.apache.jackrabbit.jcr2spi.operation.Copy;
import org.apache.jackrabbit.jcr2spi.operation.Move;
import org.apache.jackrabbit.jcr2spi.operation.Remove;
import org.apache.jackrabbit.jcr2spi.operation.SetMixin;
import org.apache.jackrabbit.jcr2spi.operation.SetPropertyValue;
import org.apache.jackrabbit.jcr2spi.operation.ReorderNodes;
import org.apache.jackrabbit.jcr2spi.operation.Operation;
import org.apache.jackrabbit.jcr2spi.operation.Checkout;
import org.apache.jackrabbit.jcr2spi.operation.Checkin;
import org.apache.jackrabbit.jcr2spi.operation.Update;
import org.apache.jackrabbit.jcr2spi.operation.Restore;
import org.apache.jackrabbit.jcr2spi.operation.ResolveMergeConflict;
import org.apache.jackrabbit.jcr2spi.operation.Merge;
import org.apache.jackrabbit.jcr2spi.operation.LockOperation;
import org.apache.jackrabbit.jcr2spi.operation.LockRefresh;
import org.apache.jackrabbit.jcr2spi.operation.LockRelease;
import org.apache.jackrabbit.jcr2spi.operation.AddLabel;
import org.apache.jackrabbit.jcr2spi.operation.RemoveLabel;
import org.apache.jackrabbit.jcr2spi.operation.RemoveVersion;
import org.apache.jackrabbit.jcr2spi.security.AccessManager;
import org.apache.jackrabbit.jcr2spi.observation.InternalEventListener;
import org.apache.jackrabbit.jcr2spi.config.CacheBehaviour;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.MalformedPathException;
import org.apache.jackrabbit.spi.RepositoryService;
import org.apache.jackrabbit.spi.SessionInfo;
import org.apache.jackrabbit.spi.NodeId;
import org.apache.jackrabbit.spi.IdFactory;
import org.apache.jackrabbit.spi.LockInfo;
import org.apache.jackrabbit.spi.QueryInfo;
import org.apache.jackrabbit.spi.QNodeDefinition;
import org.apache.jackrabbit.spi.QNodeTypeDefinitionIterator;
import org.apache.jackrabbit.spi.ItemId;
import org.apache.jackrabbit.spi.PropertyId;
import org.apache.jackrabbit.spi.Batch;
import org.apache.jackrabbit.spi.EventBundle;
import org.apache.jackrabbit.spi.EventFilter;
import org.apache.jackrabbit.spi.IdIterator;
import org.apache.jackrabbit.spi.QNodeTypeDefinition;
import org.apache.jackrabbit.spi.QValue;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.NamespaceException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.AccessDeniedException;
import javax.jcr.PathNotFoundException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.ItemExistsException;
import javax.jcr.Repository;
import javax.jcr.InvalidItemStateException;
import javax.jcr.MergeException;
import javax.jcr.Session;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.version.VersionException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.ConstraintViolationException;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.HashSet;
import java.util.Collection;
import java.util.Map;
import java.util.Collections;
import java.io.InputStream;

import EDU.oswego.cs.dl.util.concurrent.Channel;
import EDU.oswego.cs.dl.util.concurrent.Sync;
import EDU.oswego.cs.dl.util.concurrent.Latch;
import EDU.oswego.cs.dl.util.concurrent.LinkedQueue;

/**
 * <code>WorkspaceManager</code>...
 */
public class WorkspaceManager implements UpdatableItemStateManager, NamespaceStorage, AccessManager {

    private static Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private final RepositoryService service;
    private final SessionInfo sessionInfo;

    private final ItemStateManager cache;
    private final CacheBehaviour cacheBehaviour;

    private final NamespaceRegistryImpl nsRegistry;
    private final NodeTypeRegistry ntRegistry;

    /**
     * Monitor object to synchronize the feed thread with client
     * threads that call {@link #execute(Operation)} or {@link
     * #execute(ChangeLog)}.
     */
    private final Object updateMonitor = new Object();

    /**
     * A producer for this channel can request an immediate poll for events
     * by placing a Sync into the channel. The Sync is released when the event
     * poll finished.
     */
    private final Channel immediateEventRequests = new LinkedQueue();

    /**
     * This is the event polling for changes. If <code>null</code>
     * then the underlying repository service does not support observation.
     */
    private final Thread changeFeed;

    /**
     * List of event listener that are set on this WorkspaceManager to get
     * notifications about local and external changes.
     */
    private final Set listeners = Collections.synchronizedSet(new HashSet());

    public WorkspaceManager(RepositoryService service, SessionInfo sessionInfo,
                            CacheBehaviour cacheBehaviour, int pollingInterval)
        throws RepositoryException {
        this.service = service;
        this.sessionInfo = sessionInfo;
        this.cacheBehaviour = cacheBehaviour;

        cache = createItemStateManager();

        Map repositoryDescriptors = service.getRepositoryDescriptors();

        nsRegistry = createNamespaceRegistry(repositoryDescriptors);
        ntRegistry = createNodeTypeRegistry(nsRegistry, repositoryDescriptors);
        changeFeed = createChangeFeed(pollingInterval);
    }

    public NamespaceRegistryImpl getNamespaceRegistryImpl() {
        return nsRegistry;
    }

    public NodeTypeRegistry getNodeTypeRegistry() {
        return ntRegistry;
    }

    public String[] getWorkspaceNames() throws RepositoryException {
        return service.getWorkspaceNames(sessionInfo);
    }

    public IdFactory getIdFactory() {
        return service.getIdFactory();
    }

    public LockInfo getLockInfo(NodeId nodeId) throws LockException, RepositoryException {
        return service.getLockInfo(sessionInfo, nodeId);
    }

    public String[] getLockTokens() {
        return sessionInfo.getLockTokens();
    }

    /**
     * This method always succeeds.
     * This is not compliant to the requirements for {@link Session#addLockToken(String)}
     * as defined by JSR170, which defines that at most one single <code>Session</code>
     * may contain the same lock token. However, with SPI it is not possible
     * to determine, whether another session holds the lock, nor can the client
     * determine, which lock this token belongs to. The latter would be
     * necessary in order to build the 'Lock' object properly.
     *
     * TODO: check if throwing an exception would be more appropriate
     *
     * @param lt
     * @throws LockException
     * @throws RepositoryException
     */
    public void addLockToken(String lt) throws LockException, RepositoryException {
        sessionInfo.addLockToken(lt);
        /*
        // TODO: JSR170 defines that a token can be present with one session only.
        //       however, we cannot find out about another session holding the lock.
        //       and neither knows the server, which session is holding a lock token.
        */
    }

    /**
     * Tries to remove the given token from the <code>SessionInfo</code>. If the
     * SessionInfo does not contains the specified token, this method returns
     * silently.<br>
     * Note, that any restriction regarding removal of lock tokens must be asserted
     * before this method is called.
     *
     * @param lt
     * @throws LockException
     * @throws RepositoryException
     */
    public void removeLockToken(String lt) throws LockException, RepositoryException {
        sessionInfo.removeLockToken(lt);
    }

    /**
     *
     * @return
     * @throws RepositoryException
     */
    public String[] getSupportedQueryLanguages() throws RepositoryException {
        return service.getSupportedQueryLanguages(sessionInfo);
    }

    /**
     * Checks if the query statement is valid.
     *
     * @param statement  the query statement.
     * @param language   the query language.
     * @param namespaces the locally remapped namespaces which might be used in
     *                   the query statement.
     * @throws InvalidQueryException if the query statement is invalid.
     * @throws RepositoryException   if an error occurs while checking the query
     *                               statement.
     */
    public void checkQueryStatement(String statement,
                                    String language,
                                    Map namespaces)
            throws InvalidQueryException, RepositoryException {
        service.checkQueryStatement(sessionInfo, statement, language, namespaces);
    }

    /**
     * @param statement  the query statement.
     * @param language   the query language.
     * @param namespaces the locally remapped namespaces which might be used in
     *                   the query statement.
     * @return
     * @throws RepositoryException
     */
    public QueryInfo executeQuery(String statement, String language, Map namespaces)
            throws RepositoryException {
        return service.executeQuery(sessionInfo, statement, language, namespaces);
    }

    /**
     * Sets the <code>InternalEventListener</code> that gets notifications about
     * local and external changes.
     * 
     * @param listener the new listener.
     */
    public void addEventListener(InternalEventListener listener) {
        listeners.add(listener);
    }

    /**
     *
     * @param listener
     */
    public void removeEventListener(InternalEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Creates an event filter based on the parameters available in {@link
     * javax.jcr.observation.ObservationManager#addEventListener}.
     *
     * @param eventTypes   A combination of one or more event type constants
     *                     encoded as a bitmask.
     * @param path         an absolute path.
     * @param isDeep       a <code>boolean</code>.
     * @param uuids        array of UUIDs.
     * @param nodeTypes    array of node type names.
     * @param noLocal      a <code>boolean</code>.
     * @return the event filter instance with the given parameters.
     * @throws UnsupportedRepositoryOperationException
     *          if this implementation does not support observation.
     */
    public EventFilter createEventFilter(int eventTypes, Path path, boolean isDeep,
                                         String[] uuids, QName[] nodeTypes,
                                         boolean noLocal)
        throws UnsupportedRepositoryOperationException, RepositoryException {
        return service.createEventFilter(sessionInfo, eventTypes, path, isDeep, uuids, nodeTypes, noLocal);
    }
    //--------------------------------------------------------------------------
    /**
     *
     * @return
     */
    private ItemStateManager createItemStateManager() {
        ItemStateFactory isf = new WorkspaceItemStateFactory(service, sessionInfo, this);
        WorkspaceItemStateManager ism = new WorkspaceItemStateManager(this, cacheBehaviour, isf, service.getIdFactory());
        return ism;
    }

    /**
     *
     * @param descriptors
     * @return
     * @throws RepositoryException
     */
    private NamespaceRegistryImpl createNamespaceRegistry(Map descriptors) throws RepositoryException {
        boolean level2 = Boolean.valueOf((String) descriptors.get(Repository.LEVEL_2_SUPPORTED)).booleanValue();
        return new NamespaceRegistryImpl(this, level2);
    }

    /**
     *
     * @param nsRegistry
     * @param descriptors
     * @return
     * @throws RepositoryException
     */
    private NodeTypeRegistry createNodeTypeRegistry(NamespaceRegistry nsRegistry, Map descriptors) throws RepositoryException {
        QNodeDefinition rootNodeDef = service.getNodeDefinition(sessionInfo, service.getRootId(sessionInfo));
        QNodeTypeDefinitionIterator it = service.getNodeTypeDefinitions(sessionInfo);
        List ntDefs = new ArrayList();
        while (it.hasNext()) {
            ntDefs.add(it.nextDefinition());
        }
        NodeTypeStorage ntst = new NodeTypeStorage() {
            public void registerNodeTypes(QNodeTypeDefinition[] nodeTypeDefs) throws NoSuchNodeTypeException, RepositoryException {
                throw new UnsupportedOperationException("NodeType registration not yet defined by the SPI");
            }
            public void reregisterNodeTypes(QNodeTypeDefinition[] nodeTypeDefs) throws NoSuchNodeTypeException, RepositoryException {
                throw new UnsupportedOperationException("NodeType registration not yet defined by the SPI");
            }
            public void unregisterNodeTypes(QName[] nodeTypeNames) throws NoSuchNodeTypeException, RepositoryException {
                throw new UnsupportedOperationException("NodeType registration not yet defined by the SPI");
            }
        };
        return NodeTypeRegistryImpl.create(ntDefs, ntst, rootNodeDef, nsRegistry);
    }

    /**
     * Creates a background thread which polls for external changes on the
     * RepositoryService.
     *
     * @param pollingInterval the polling interval in milliseconds.
     * @return the background polling thread or <code>null</code> if the underlying
     *         <code>RepositoryService</code> does not support observation.
     */
    private Thread createChangeFeed(int pollingInterval) {
        Thread t = new Thread(new ChangePolling(pollingInterval));
        t.setName("Change Polling");
        t.setDaemon(true);
        t.start();
        return t;
    }

    //---------------------------------------------------< ItemStateManager >---
    /**
     * @inheritDoc
     * @see ItemStateManager#getRootState()
     */
    public NodeState getRootState() throws ItemStateException {
        // retrieve through cache
        synchronized (cache) {
            return cache.getRootState();
        }
    }

    /**
     * @inheritDoc
     * @see ItemStateManager#getItemState(ItemId)
     */
    public ItemState getItemState(ItemId id) throws NoSuchItemStateException, ItemStateException {
        // retrieve through cache
        synchronized (cache) {
            return cache.getItemState(id);
        }
    }

    /**
     * @inheritDoc
     * @see ItemStateManager#hasItemState(ItemId)
     */
    public boolean hasItemState(ItemId id) {
        synchronized (cache) {
            return cache.hasItemState(id);
        }
    }

    /**
     * @inheritDoc
     * @see ItemStateManager#getReferingStates(NodeState)
     * @param nodeState
     */
    public Collection getReferingStates(NodeState nodeState) throws ItemStateException {
        synchronized (cache) {
            return cache.getReferingStates(nodeState);
        }
    }

    /**
     * @inheritDoc
     * @see ItemStateManager#hasReferingStates(NodeState)
     * @param nodeState
     */
    public boolean hasReferingStates(NodeState nodeState) {
        synchronized (cache) {
            return cache.hasReferingStates(nodeState);
        }
    }

    //------ updatable -:>> review ---------------------------------------------
    /**
     * Creates a new batch from the single workspace operation and executes it.
     *
     * @see UpdatableItemStateManager#execute(Operation)
     */
    public void execute(Operation operation) throws RepositoryException {
        if (cacheBehaviour == CacheBehaviour.OBSERVATION) {
            Sync eventSignal;
            synchronized (updateMonitor) {
                new OperationVisitorImpl(sessionInfo).execute(operation);
                eventSignal = getEventPollingRequest();
            }
            try {
                eventSignal.acquire();
            } catch (InterruptedException e) {
                Thread.interrupted();
                log.warn("Interrupted while waiting for events from RepositoryService");
            }
        } else {
            // execute operation and delegate invalidation of affected item
            // states to the operation.
            new OperationVisitorImpl(sessionInfo).execute(operation);
            operation.persisted();
        }
    }

    /**
     * Creates a new batch from the given <code>ChangeLog</code> and executes it.
     *
     * @param changes
     * @throws RepositoryException
     */
    public void execute(ChangeLog changes) throws RepositoryException {
        if (cacheBehaviour == CacheBehaviour.OBSERVATION) {
            // TODO: TOBEFIXED. processing events after changelog may lead to consistency problems (duplicate processing) (e.g. removal of SNSs).
            // TODO: filtering of events required according to information present in the changelog.
            Sync eventSignal;
            synchronized (updateMonitor) {
                new OperationVisitorImpl(sessionInfo).execute(changes);
                changes.persisted(cacheBehaviour);
                eventSignal = getEventPollingRequest();
            }
            try {
                // wait at most 10 seconds
                if (!eventSignal.attempt(10 * 1000)) {
                    log.warn("No events received for batch");
                }
            } catch (InterruptedException e) {
                Thread.interrupted();
                log.warn("Interrupted while waiting for events from RepositoryService");
            }
        } else {
            new OperationVisitorImpl(sessionInfo).execute(changes);
            changes.persisted(cacheBehaviour);
        }
    }

    /**
     * Dispose this <code>WorkspaceManager</code>
     */
    public void dispose() {
        if (changeFeed != null) {
            changeFeed.interrupt();
            try {
                changeFeed.join();
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for external change thread to terminate.");
            }
        }
        try {
            service.dispose(sessionInfo);
        } catch (RepositoryException e) {
            log.warn("Exception while disposing session info: " + e);            
        }
    }
    //------------------------------------------------------< AccessManager >---
    /**
     * @see AccessManager#isGranted(NodeState, Path, String[])
     */
    public boolean isGranted(NodeState parentState, Path relPath, String[] actions) throws ItemNotFoundException, RepositoryException {
        // TODO: TOBEFIXED. 
        ItemState wspState = parentState.getWorkspaceState();
        if (wspState == null) {
            Path.PathBuilder pb = new Path.PathBuilder();
            pb.addAll(relPath.getElements());
            while (wspState == null) {
                pb.addFirst(parentState.getQName());

                parentState = parentState.getParent();
                wspState = parentState.getWorkspaceState();
            }
            try {
                relPath = pb.getPath();
            } catch (MalformedPathException e) {
                throw new RepositoryException(e);
            }
        }


        if (wspState == null) {
            // internal error. should never occur
            throw new RepositoryException("Internal error: Unable to retrieve overlayed state in hierarchy.");
        } else {
            NodeId parentId = ((NodeState)parentState).getNodeId();
            // TODO: 'createNodeId' is basically wrong since isGranted is unspecific for any item.
            ItemId id = getIdFactory().createNodeId(parentId, relPath);
            return service.isGranted(sessionInfo, id, actions);
        }
    }

    /**
     * @see AccessManager#isGranted(ItemState, String[])
     */
    public boolean isGranted(ItemState itemState, String[] actions) throws ItemNotFoundException, RepositoryException {
        ItemState wspState = itemState.getWorkspaceState();
        // a 'new' state can always be read, written and removed
        // TODO: correct?
        if (wspState == null) {
            return true;
        }
        return service.isGranted(sessionInfo, wspState.getId(), actions);
    }

    /**
     * @see AccessManager#canRead(ItemState)
     */
    public boolean canRead(ItemState itemState) throws ItemNotFoundException, RepositoryException {
        ItemState wspState = itemState.getWorkspaceState();
        // a 'new' state can always be read
        if (wspState == null) {
            return true;
        }
        return service.isGranted(sessionInfo, wspState.getId(), AccessManager.READ);
    }

    /**
     * @see AccessManager#canRemove(ItemState)
     */
    public boolean canRemove(ItemState itemState) throws ItemNotFoundException, RepositoryException {
        ItemState wspState = itemState.getWorkspaceState();
        // a 'new' state can always be removed again
        if (wspState == null) {
            return true;
        }
        return service.isGranted(sessionInfo, wspState.getId(), AccessManager.REMOVE);
    }

    /**
     * @see AccessManager#canAccess(String)
     */
    public boolean canAccess(String workspaceName) throws NoSuchWorkspaceException, RepositoryException {
        String[] wspNames = getWorkspaceNames();
        for (int i = 0; i < wspNames.length; i++) {
            if (wspNames[i].equals(wspNames)) {
                return true;
            }
        }
        return false;
    }

    //---------------------------------------------------------< XML import >---
    public void importXml(NodeState parentState, InputStream xmlStream, int uuidBehaviour) throws RepositoryException, LockException, ConstraintViolationException, AccessDeniedException, UnsupportedRepositoryOperationException, ItemExistsException, VersionException {
        NodeId parentId = parentState.getNodeId();
        service.importXml(sessionInfo, parentId, xmlStream, uuidBehaviour);
    }

    //---------------------------------------------------< NamespaceStorage >---

    public Map getRegisteredNamespaces() throws RepositoryException {
        return service.getRegisteredNamespaces(sessionInfo);
    }

    /**
     * @inheritDoc
     */
    public void registerNamespace(String prefix, String uri) throws NamespaceException, UnsupportedRepositoryOperationException, AccessDeniedException, RepositoryException {
        service.registerNamespace(sessionInfo, prefix, uri);
    }

    /**
     * @inheritDoc
     */
    public void unregisterNamespace(String uri) throws NamespaceException, UnsupportedRepositoryOperationException, AccessDeniedException, RepositoryException {
        service.unregisterNamespace(sessionInfo, uri);
    }

    //--------------------------------------------------------------------------

    /**
     * Called when local or external events occured. This method is called after
     * changes have been applied to the repository.
     *
     * @param eventBundles the event bundles generated by the repository service
     * as the effect of an local or external change.
     */
    private void onEventReceived(EventBundle[] eventBundles) {
        // notify listener
        InternalEventListener[] lstnrs = (InternalEventListener[]) listeners.toArray(new InternalEventListener[listeners.size()]);
        for (int i = 0; i < eventBundles.length; i++) {
            for (int j = 0; j < lstnrs.length; j++) {
                lstnrs[j].onEvent(eventBundles[i]);
            }
        }
    }

    /**
     * Executes a sequence of operations on the repository service within
     * a given <code>SessionInfo</code>.
     */
    private final class OperationVisitorImpl implements OperationVisitor {

        /**
         * The session info for all operations in this batch.
         */
        private final SessionInfo sessionInfo;

        private Batch batch;

        private OperationVisitorImpl(SessionInfo sessionInfo) {
            this.sessionInfo = sessionInfo;
        }

        /**
         * Executes the operations on the repository service.
         */
        private void execute(ChangeLog changeLog) throws RepositoryException, ConstraintViolationException, AccessDeniedException, ItemExistsException, NoSuchNodeTypeException, UnsupportedRepositoryOperationException, VersionException {
            try {
                ItemState target = changeLog.getTarget();
                batch = service.createBatch(target.getId(), sessionInfo);
                Iterator it = changeLog.getOperations();
                while (it.hasNext()) {
                    Operation op = (Operation) it.next();
                    log.info("executing: " + op);
                    op.accept(this);
                }
            } finally {
                if (batch != null) {
                    service.submit(batch);
                    // reset batch field
                    batch = null;
                }
            }
        }

        /**
         * Executes the operations on the repository service.
         */
        private void execute(Operation workspaceOperation) throws RepositoryException, ConstraintViolationException, AccessDeniedException, ItemExistsException, NoSuchNodeTypeException, UnsupportedRepositoryOperationException, VersionException {
            log.info("executing: " + workspaceOperation);
            workspaceOperation.accept(this);
        }

        //-----------------------< OperationVisitor >---------------------------
        /**
         * @inheritDoc
         * @see OperationVisitor#visit(AddNode)
         */
        public void visit(AddNode operation) throws RepositoryException {
            NodeId parentId = operation.getParentState().getNodeId();
            batch.addNode(parentId, operation.getNodeName(), operation.getNodeTypeName(), operation.getUuid());
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(AddProperty)
         */
        public void visit(AddProperty operation) throws RepositoryException {
            NodeId parentId = operation.getParentState().getNodeId();
            QName propertyName = operation.getPropertyName();
            int type = operation.getPropertyType();
            if (operation.isMultiValued()) {
                batch.addProperty(parentId, propertyName, operation.getValues());
            } else {
                QValue value = operation.getValues()[0];
                batch.addProperty(parentId, propertyName, value);
            }
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(Clone)
         */
        public void visit(Clone operation) throws NoSuchWorkspaceException, LockException, ConstraintViolationException, AccessDeniedException, ItemExistsException, UnsupportedRepositoryOperationException, VersionException, RepositoryException {
            NodeId nId = operation.getNodeState().getNodeId();
            NodeId destParentId = operation.getDestinationParentState().getNodeId();
            service.clone(sessionInfo, operation.getWorkspaceName(), nId, destParentId, operation.getDestinationName(), operation.isRemoveExisting());
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(Copy)
         */
        public void visit(Copy operation) throws NoSuchWorkspaceException, LockException, ConstraintViolationException, AccessDeniedException, ItemExistsException, UnsupportedRepositoryOperationException, VersionException, RepositoryException {
            NodeId nId = operation.getNodeState().getNodeId();
            NodeId destParentId = operation.getDestinationParentState().getNodeId();
            service.copy(sessionInfo, operation.getWorkspaceName(), nId, destParentId, operation.getDestinationName());
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(Move)
         */
        public void visit(Move operation) throws LockException, ConstraintViolationException, AccessDeniedException, ItemExistsException, UnsupportedRepositoryOperationException, VersionException, RepositoryException {
            NodeId moveId = operation.getSourceId();
            NodeId destParentId = operation.getDestinationParentState().getNodeId();
            if (batch == null) {
                service.move(sessionInfo, moveId, destParentId, operation.getDestinationName());
            } else {
                batch.move(moveId, destParentId, operation.getDestinationName());
            }
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(Update)
         */
        public void visit(Update operation) throws NoSuchWorkspaceException, AccessDeniedException, LockException, InvalidItemStateException, RepositoryException {
            NodeId nId = operation.getNodeState().getNodeId();
            service.update(sessionInfo, nId, operation.getSourceWorkspaceName());
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(Remove)
         */
        public void visit(Remove operation) throws RepositoryException {
            ItemId id = operation.getRemoveState().getId();
            batch.remove(id);
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(SetMixin)
         */
        public void visit(SetMixin operation) throws RepositoryException {
            batch.setMixins(operation.getNodeState().getNodeId(), operation.getMixinNames());
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(SetPropertyValue)
         */
        public void visit(SetPropertyValue operation) throws RepositoryException {
            PropertyState pState = operation.getPropertyState();
            PropertyId id = pState.getPropertyId();
            if (pState.isMultiValued()) {
                batch.setValue(id, operation.getValues());
            } else {
                batch.setValue(id, operation.getValues()[0]);
            }
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(ReorderNodes)
         */
        public void visit(ReorderNodes operation) throws RepositoryException {
            NodeId parentId = operation.getParentState().getNodeId();
            NodeId insertId = operation.getInsertNode().getNodeId();
            NodeId beforeId = null;
            if (operation.getBeforeNode() != null) {
                beforeId = operation.getBeforeNode().getNodeId() ;
            }
            batch.reorderNodes(parentId, insertId, beforeId);
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(Checkout)
         */
        public void visit(Checkout operation) throws UnsupportedRepositoryOperationException, LockException, RepositoryException {
            service.checkout(sessionInfo, operation.getNodeState().getNodeId());
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(Checkin)
         */
        public void visit(Checkin operation) throws UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
            service.checkin(sessionInfo, operation.getNodeState().getNodeId());
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(Restore)
         */
        public void visit(Restore operation) throws VersionException, PathNotFoundException, ItemExistsException, UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
            NodeState nState = operation.getNodeState();
            NodeState[] versionStates = operation.getVersionStates();
            if (versionStates == null || versionStates.length == 0) {
                throw new IllegalArgumentException("Restore must specify at least a singe version.");
            }

            NodeId[] vIds = new NodeId[versionStates.length];
            for (int i = 0; i < vIds.length; i++) {
                vIds[i] = versionStates[i].getNodeId();
            }

            if (nState == null) {
                service.restore(sessionInfo, vIds, operation.removeExisting());
            } else {
                if (vIds.length > 1) {
                    throw new IllegalArgumentException("Restore from a single node must specify but one single Version.");
                }

                NodeId targetId;
                Path relPath = operation.getRelativePath();
                if (relPath != null) {
                    targetId = getIdFactory().createNodeId(nState.getNodeId(), relPath);
                } else {
                    targetId = nState.getNodeId();
                }
                service.restore(sessionInfo, targetId, vIds[0], operation.removeExisting());
            }
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(Merge)
         */
        public void visit(Merge operation) throws NoSuchWorkspaceException, AccessDeniedException, MergeException, LockException, InvalidItemStateException, RepositoryException {
            NodeId nId = operation.getNodeState().getNodeId();
            IdIterator failed = service.merge(sessionInfo, nId, operation.getSourceWorkspaceName(), operation.bestEffort());
            operation.setFailedIds(failed);
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(ResolveMergeConflict)
         */
        public void visit(ResolveMergeConflict operation) throws VersionException, InvalidItemStateException, UnsupportedRepositoryOperationException, RepositoryException {
            try {
                NodeId nId = operation.getNodeState().getNodeId();
                NodeId vId = operation.getVersionState().getNodeId();

                PropertyState mergeFailedState = (PropertyState) cache.getItemState(
                        getIdFactory().createPropertyId(nId, QName.JCR_MERGEFAILED));

                QValue[] vs = mergeFailedState.getValues();

                NodeId[] mergeFailedIds = new NodeId[vs.length - 1];
                for (int i = 0, j = 0; i < vs.length; i++) {
                    NodeId id = getIdFactory().createNodeId(vs[i].getString());
                    if (!id.equals(vId)) {
                        mergeFailedIds[j] = id;
                        j++;
                    }
                    // else: the version id is being solved by this call and not
                    // part of 'jcr:mergefailed' any more
                }

                PropertyState predecessorState = (PropertyState) cache.getItemState(
                        getIdFactory().createPropertyId(nId, QName.JCR_PREDECESSORS));

                vs = predecessorState.getValues();

                boolean resolveDone = operation.resolveDone();
                int noOfPredecessors = (resolveDone) ? vs.length + 1 : vs.length;
                NodeId[] predecessorIds = new NodeId[noOfPredecessors];

                int i = 0;
                while (i < vs.length) {
                    predecessorIds[i] = getIdFactory().createNodeId(vs[i].getString());
                    i++;
                }
                if (resolveDone) {
                    predecessorIds[i] = vId;
                }
                service.resolveMergeConflict(sessionInfo, nId, mergeFailedIds, predecessorIds);
            } catch (ItemStateException e) {
                // should not occur.
                throw new RepositoryException(e);
            }
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(LockOperation)
         */
        public void visit(LockOperation operation) throws AccessDeniedException, InvalidItemStateException, UnsupportedRepositoryOperationException, LockException, RepositoryException {
            NodeId nId = operation.getNodeState().getNodeId();
            LockInfo lInfo = service.lock(sessionInfo, nId, operation.isDeep(), operation.isSessionScoped());
            operation.setLockInfo(lInfo);
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(LockRefresh)
         */
        public void visit(LockRefresh operation) throws AccessDeniedException, InvalidItemStateException, UnsupportedRepositoryOperationException, LockException, RepositoryException {
            NodeId nId = operation.getNodeState().getNodeId();
            service.refreshLock(sessionInfo, nId);
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(LockRelease)
         */
        public void visit(LockRelease operation) throws AccessDeniedException, InvalidItemStateException, UnsupportedRepositoryOperationException, LockException, RepositoryException {
            NodeId nId = operation.getNodeState().getNodeId();
            service.unlock(sessionInfo, nId);
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(AddLabel)
         */
        public void visit(AddLabel operation) throws VersionException, RepositoryException {
            NodeId vhId = operation.getVersionHistoryState().getNodeId();
            NodeId vId = operation.getVersionState().getNodeId();
            service.addVersionLabel(sessionInfo, vhId, vId, operation.getLabel(), operation.moveLabel());
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(RemoveLabel)
         */
        public void visit(RemoveLabel operation) throws VersionException, RepositoryException {
            NodeId vhId = operation.getVersionHistoryState().getNodeId();
            NodeId vId = operation.getVersionState().getNodeId();
            service.removeVersionLabel(sessionInfo, vhId, vId, operation.getLabel());
        }

        /**
         * @inheritDoc
         * @see OperationVisitor#visit(RemoveVersion)
         */
        public void visit(RemoveVersion operation) throws VersionException, AccessDeniedException, ReferentialIntegrityException, RepositoryException {
            NodeState versionState = (NodeState) operation.getRemoveState();
            NodeState vhState = operation.getParentState();
            service.removeVersion(sessionInfo, vhState.getNodeId(), versionState.getNodeId());
        }
    }

    /**
     * Requests an immediate poll for events. The returned Sync will be
     * released by the event polling thread when events have been retrieved.
     */
    private Sync getEventPollingRequest() {
        Sync signal;
        if (changeFeed != null) {
            // observation supported
            signal = new Latch();
            try {
                immediateEventRequests.put(signal);
            } catch (InterruptedException e) {
                log.warn("Unable to request immediate event poll: " + e);
            }
        } else {
            // no observation, return a dummy sync which can be acquired immediately
            signal = new Sync() {
                public void acquire() {
                }
                public boolean attempt(long l) {
                    return true;
                }
                public void release() {
                    throw new UnsupportedOperationException();
                }
            };
        }
        return signal;
    }

    /**
     * Implements the polling for changes on the repository service.
     */
    private final class ChangePolling implements Runnable {

        /**
         * The polling interval in milliseconds.
         */
        private final int pollingInterval;

        /**
         * Creates a new change polling with a given polling interval.
         *
         * @param pollingInterval the interval in milliseconds.
         */
        private ChangePolling(int pollingInterval) {
            this.pollingInterval = pollingInterval;
        }

        public void run() {
            while (!Thread.interrupted()) {
                try {
                    // wait for a signal to do an immediate poll but wait at
                    // most EXTERNAL_EVENT_POLLING_INTERVAL
                    Sync signal = (Sync) immediateEventRequests.poll(pollingInterval);

                    synchronized (updateMonitor) {
                        // if this thread was waiting for updateMonitor and now
                        // enters this synchronized block, then a user thread
                        // has just finished an operation and will probably
                        // request an immediate event poll. That's why we
                        // check here again for a sync signal
                        if (signal == null) {
                            signal = (Sync) immediateEventRequests.poll(0);
                        }

                        if (signal != null) {
                            log.debug("Request for immediate event poll");
                        }

                        long timeout = 0;
                        // get filters from listeners
                        List filters = new ArrayList();
                        InternalEventListener[] iel = (InternalEventListener[]) listeners.toArray(new InternalEventListener[0]);
                        for (int i = 0; i < iel.length; i++) {
                            filters.addAll(iel[i].getEventFilters());
                        }
                        EventFilter[] filtArr = (EventFilter[]) filters.toArray(new EventFilter[filters.size()]);

                        EventBundle[] bundles = service.getEvents(sessionInfo, timeout, filtArr);
                        try {
                            if (bundles.length > 0) {
                                onEventReceived(bundles);
                            }
                        } finally {
                            if (signal != null) {
                                log.debug("About to signal that events have been delivered");
                                signal.release();
                                log.debug("Event delivery signaled");
                            }
                        }
                    }
                } catch (UnsupportedRepositoryOperationException e) {
                    log.error("SPI implementation does not support observation: " + e);
                    // terminate
                    break;
                } catch (RepositoryException e) {
                    log.warn("Exception while retrieving event bundles: " + e);
                    log.debug("Dump:", e);
                } catch (InterruptedException e) {
                    // terminate
                    break;
                } catch (Exception e) {
                    log.warn("Exception in event polling thread: " + e);
                    log.debug("Dump:", e);
                }
            }
        }
    }
}
