/*
 * Copyright 2004-2005 The Apache Software Foundation or its licensors,
 *                     as applicable.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.version.persistence;

import org.apache.jackrabbit.core.InternalValue;
import org.apache.jackrabbit.core.NamespaceRegistryImpl;
import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.QName;
import org.apache.jackrabbit.core.state.UpdateOperation;
import org.apache.jackrabbit.core.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.core.util.Text;
import org.apache.jackrabbit.core.util.uuid.UUID;
import org.apache.jackrabbit.core.version.*;
import org.apache.log4j.Logger;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.version.VersionException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;

/**
 *
 */
class InternalVersionHistoryImpl extends InternalVersionItemImpl implements InternalVersionHistory {
    /**
     * default logger
     */
    private static Logger log = Logger.getLogger(InternalVersionHistory.class);

    /**
     * the cache of the version labels
     * key = version label (String)
     * value = version
     */
    private HashMap labelCache = new HashMap();

    /**
     * the root version of this history
     */
    private InternalVersion rootVersion;

    /**
     * the hashmap of all versions
     * key = versionId (String)
     * value = version
     */
    private HashMap versionCache = new HashMap();

    /**
     * The nodes state of this version history
     */
    private PersistentNode node;

    /**
     * the node that holds the label nodes
     */
    private PersistentNode labelNode;

    /**
     * the id of this history
     */
    private String historyId;

    /**
     * the if of the versionable node
     */
    private String versionableId;

    /**
     * Creates a new VersionHistory object for the given node state.
     */
    InternalVersionHistoryImpl(PersistentVersionManager vMgr, PersistentNode node) throws RepositoryException {
        super(vMgr);
        this.node = node;
        init();
    }

    /**
     * Initialies the history and loads all internal caches
     *
     * @throws RepositoryException
     */
    private void init() throws RepositoryException {
        versionCache.clear();
        labelCache.clear();

        // get id
        historyId = (String) node.getPropertyValue(NativePVM.PROPNAME_HISTORY_ID).internalValue();

        // get versionable id
        versionableId = (String) node.getPropertyValue(NativePVM.PROPNAME_VERSIONABLE_ID).internalValue();

        // get entries
        PersistentNode[] children = node.getChildNodes();
        for (int i = 0; i < children.length; i++) {
            PersistentNode child = children[i];
            if (child.getName().equals(NativePVM.NODENAME_VERSION_LABELS)) {
                labelNode = child;
                continue;
            }
            InternalVersionImpl v = new InternalVersionImpl(this, child);
            versionCache.put(v.getId(), v);
            if (v.isRootVersion()) {
                rootVersion = v;
            }
        }

        // resolve successors and predecessors
        Iterator iter = versionCache.values().iterator();
        while (iter.hasNext()) {
            InternalVersionImpl v = (InternalVersionImpl) iter.next();
            v.resolvePredecessors();
        }

        // init label cache
        PersistentNode labels[] = labelNode.getChildNodes();
        for (int i = 0; i < labels.length; i++) {
            PersistentNode lNode = labels[i];
            String name = (String) lNode.getPropertyValue(NativePVM.PROPNAME_NAME).internalValue();
            String ref = (String) lNode.getPropertyValue(NativePVM.PROPNAME_VERSION).internalValue();
            InternalVersionImpl v = (InternalVersionImpl) getVersion(ref);
            labelCache.put(name, v);
            v.internalAddLabel(name);
        }
    }

    /**
     * Returns the id of this version history
     *
     * @return
     */
    public String getId() {
        return historyId;
    }

    protected String getPersistentId() {
        return node.getUUID();
    }

    public InternalVersionItem getParent() {
        return null;
    }

    /**
     * @see javax.jcr.version.VersionHistory#getRootVersion()
     */
    public InternalVersion getRootVersion() {
        return rootVersion;
    }

    /**
     * @see javax.jcr.version.VersionHistory#getVersion(java.lang.String)
     */
    public InternalVersion getVersion(QName versionName) throws VersionException {
        // maybe add cache by name?
        Iterator iter = versionCache.values().iterator();
        while (iter.hasNext()) {
            InternalVersion v = (InternalVersion) iter.next();
            if (v.getName().equals(versionName)) {
                return v;
            }
        }
        throw new VersionException("Version " + versionName + " does not exist.");
    }

    /**
     * @param versionName
     * @return
     */
    public boolean hasVersion(QName versionName) {
        // maybe add cache?
        Iterator iter = versionCache.values().iterator();
        while (iter.hasNext()) {
            InternalVersion v = (InternalVersion) iter.next();
            if (v.getName().equals(versionName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the version for the given uuid exists
     *
     * @param uuid
     * @return
     */
    public boolean hasVersion(String uuid) {
        return versionCache.containsKey(uuid);
    }

    /**
     * Returns the version with the given uuid or <code>null</code> if the
     * respective version does not exist.
     *
     * @param uuid
     * @return
     */
    public InternalVersion getVersion(String uuid) {
        return (InternalVersion) versionCache.get(uuid);
    }

    /**
     * @see javax.jcr.version.VersionHistory#getVersionByLabel(java.lang.String)
     */
    public InternalVersion getVersionByLabel(String label) {
        return (InternalVersion) labelCache.get(label);
    }

    /**
     * Removes the indicated version from this VersionHistory. If the specified
     * vesion does not exist, if it specifies the root version or if it is
     * referenced by any node e.g. as base version, a VersionException is thrown.
     * <p/>
     * all successors of the removed version become successors of the
     * predecessors of the removed version and vice versa. then, the entire
     * version node and all its subnodes are removed.
     *
     * @param versionName
     * @throws VersionException
     */
    public void removeVersion(QName versionName) throws VersionException {
        getVersionManager().removeVersion(this, versionName);
    }

    /**
     * Removes the indicated version from this VersionHistory. If the specified
     * vesion does not exist, if it specifies the root version or if it is
     * referenced by any node e.g. as base version, a VersionException is thrown.
     * <p/>
     * all successors of the removed version become successors of the
     * predecessors of the removed version and vice versa. then, the entire
     * version node and all its subnodes are removed.
     *
     * @param versionName
     * @throws VersionException
     */
    protected void removeVersion(UpdateOperation upd, QName versionName) throws VersionException {

        try {
            InternalVersionImpl v = (InternalVersionImpl) getVersion(versionName);
            if (v.equals(rootVersion)) {
                String msg = "Removal of " + versionName + " not allowed.";
                log.error(msg);
                throw new VersionException(msg);
            }

            // remove from persistance state
            node.removeNode(upd, v.getName());

            // unregister from labels
            String[] labels = v.internalGetLabels();
            for (int i = 0; i < labels.length; i++) {
                v.internalRemoveLabel(labels[i]);
                QName name = new QName("", Text.md5(labels[i]));
                labelNode.removeNode(upd, name);
            }
            // detach from the version graph
            v.internalDetach(upd);

            // and remove from history
            versionCache.remove(v.getId());

            // store changes
            node.store(upd);
        } catch (RepositoryException e) {
            throw new VersionException("error while storing modifications", e);
        }
    }

    /**
     * @see InternalVersionHistory#addVersionLabel(org.apache.jackrabbit.core.QName, String, boolean)
     */
    public InternalVersion addVersionLabel(QName versionName, String label, boolean move)
            throws VersionException {
        return getVersionManager().addVersionLabel(this, versionName, label, move);
    }

    /**
     * @see InternalVersionHistory#addVersionLabel(org.apache.jackrabbit.core.QName, String, boolean)
     */
    protected InternalVersion addVersionLabel(UpdateOperation upd, QName versionName, String label, boolean move)
            throws VersionException {

        InternalVersion version = getVersion(versionName);
        if (version == null) {
            throw new VersionException("Version " + versionName + " does not exist in this version history.");
        }

        InternalVersionImpl prev = (InternalVersionImpl) labelCache.get(label);
        if (version.equals(prev)) {
            // ignore
            return version;
        } else if (prev != null && !move) {
            // already defined elsewhere, throw
            throw new VersionException("Version label " + label + " already defined for version " + prev.getName());
        } else if (prev != null) {
            // if already defined, but move, remove old label first
            removeVersionLabel(label);
        }
        labelCache.put(label, version);
        ((InternalVersionImpl) version).internalAddLabel(label);
        QName name = new QName("", Text.md5(label));
        try {
            PersistentNode lNode = labelNode.addNode(upd, name, NodeTypeRegistry.NT_UNSTRUCTURED);
            lNode.setPropertyValue(upd, NativePVM.PROPNAME_NAME, InternalValue.create(label));
            lNode.setPropertyValue(upd, NativePVM.PROPNAME_VERSION, InternalValue.create(version.getId()));
            labelNode.store(upd);
        } catch (RepositoryException e) {
            throw new VersionException("Error while storing modifications", e);
        }
        return prev;
    }

    /**
     * @see InternalVersionHistory#removeVersionLabel(String)
     */
    public InternalVersion removeVersionLabel(String label) throws VersionException {
        return getVersionManager().removeVersionLabel(this, label);
    }

    /**
     * @see InternalVersionHistory#removeVersionLabel(String)
     */
    protected InternalVersion removeVersionLabel(UpdateOperation upd, String label) throws VersionException {
        InternalVersionImpl v = (InternalVersionImpl) labelCache.remove(label);
        if (v == null) {
            throw new VersionException("Version label " + label + " is not in version history.");
        }
        v.internalRemoveLabel(label);
        QName name = new QName("", Text.md5(label));

        try {
            labelNode.removeNode(upd, name);
            labelNode.store(upd);
        } catch (RepositoryException e) {
            throw new VersionException("Unable to store modifications", e);
        }

        return v;
    }

    /**
     * Checks in a node. It creates a new version with the given name and freezes
     * the state of the given node.
     *
     * @param name
     * @param src
     * @return
     * @throws RepositoryException
     */
    protected InternalVersionImpl checkin(UpdateOperation upd, QName name, NodeImpl src)
            throws RepositoryException {

        // copy predecessors from src node
        Value[] preds = src.getProperty(VersionManager.PROPNAME_PREDECESSORS).getValues();
        InternalValue[] predecessors = new InternalValue[preds.length];
        for (int i = 0; i < preds.length; i++) {
            String predId = preds[i].getString();
            // check if version exist
            if (!versionCache.containsKey(predId)) {
                throw new RepositoryException("invalid predecessor in source node");
            }
            predecessors[i] = InternalValue.create(predId);
        }

        String versionId = UUID.randomUUID().toString();
        QName nodeName = new QName(NamespaceRegistryImpl.NS_DEFAULT_URI, versionId);
        PersistentNode vNode = node.addNode(upd, nodeName, NativePVM.NT_REP_VERSION);
        vNode.setPropertyValue(upd, NativePVM.PROPNAME_VERSION_ID, InternalValue.create(versionId));
        vNode.setPropertyValue(upd, NativePVM.PROPNAME_VERSION_NAME, InternalValue.create(name));

        // initialize 'created' and 'predecessors'
        vNode.setPropertyValue(upd, VersionManager.PROPNAME_CREATED, InternalValue.create(Calendar.getInstance()));
        vNode.setPropertyValues(upd, VersionManager.PROPNAME_PREDECESSORS, PropertyType.STRING, predecessors);

        // checkin source node
        InternalFrozenNodeImpl.checkin(upd, vNode, VersionManager.NODENAME_FROZEN, src, false, false);

        // and store
        node.store(upd);

        // update version graph
        InternalVersionImpl version = new InternalVersionImpl(this, vNode);
        version.resolvePredecessors();

        // update cache
        versionCache.put(version.getId(), version);

        return version;
    }

    /**
     * Returns an iterator over all versions (not ordered yet)
     *
     * @return
     */
    public Iterator getVersions() {
        return versionCache.values().iterator();
    }

    /**
     * Returns the number of versions
     *
     * @return
     */
    public int getNumVersions() {
        return versionCache.size();
    }

    /**
     * @see org.apache.jackrabbit.core.version.InternalVersionHistory#getVersionableUUID()
     */
    public String getVersionableUUID() {
        return versionableId;
    }

    /**
     * @see org.apache.jackrabbit.core.version.InternalVersionHistory#getVersionLabels()
     */
    public String[] getVersionLabels() {
        return (String[]) labelCache.keySet().toArray(new String[labelCache.size()]);
    }

    protected String getUUID() {
        return node.getUUID();
    }

    protected PersistentNode getNode() {
        return node;
    }

    /**
     * Creates a new <code>InternalVersionHistory</code> below the given parent
     * node and with the given name.
     *
     * @param parent
     * @param name
     * @return
     * @throws RepositoryException
     */
    protected static InternalVersionHistoryImpl create(UpdateOperation upd, PersistentVersionManager vMgr, PersistentNode parent, String historyId, QName name, NodeImpl src)
            throws RepositoryException {

        // create history node
        PersistentNode pNode = parent.addNode(upd, name, NativePVM.NT_REP_VERSION_HISTORY);
        pNode.setPropertyValue(upd, NativePVM.PROPNAME_HISTORY_ID, InternalValue.create(historyId));

        // set the versionable uuid
        pNode.setPropertyValue(upd, NativePVM.PROPNAME_VERSIONABLE_ID, InternalValue.create(src.internalGetUUID()));

        // create label node
        pNode.addNode(upd, NativePVM.NODENAME_VERSION_LABELS, NodeTypeRegistry.NT_UNSTRUCTURED);

        // create root version
        String versionId = UUID.randomUUID().toString();
        QName nodeName = new QName(NamespaceRegistryImpl.NS_DEFAULT_URI, versionId);

        PersistentNode vNode = pNode.addNode(upd, nodeName, NativePVM.NT_REP_VERSION);
        vNode.setPropertyValue(upd, NativePVM.PROPNAME_VERSION_ID, InternalValue.create(versionId));
        vNode.setPropertyValue(upd, NativePVM.PROPNAME_VERSION_NAME, InternalValue.create(VersionManager.NODENAME_ROOTVERSION));

        // initialize 'created' and 'predecessors'
        vNode.setPropertyValue(upd, VersionManager.PROPNAME_CREATED, InternalValue.create(Calendar.getInstance()));
        vNode.setPropertyValues(upd, VersionManager.PROPNAME_PREDECESSORS, PropertyType.REFERENCE, new InternalValue[0]);

        // add also an empty frozen node to the root version
        InternalFrozenNodeImpl.checkin(upd, vNode, VersionManager.NODENAME_FROZEN, src, true, false);

        parent.store(upd);
        return new InternalVersionHistoryImpl(vMgr, pNode);
    }
}
