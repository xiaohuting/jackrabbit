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
package org.apache.jackrabbit.core.query.lucene;

import org.apache.log4j.Logger;

import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.io.IOException;

/**
 * Implements the recovery process.
 */
class Recovery {

    /**
     * The logger instance for this class.
     */
    private static final Logger log = Logger.getLogger(Recovery.class);

    /**
     * The MultiIndex where to run the recovery on.
     */
    private final MultiIndex index;

    /**
     * The redo redoLog.
     */
    private final RedoLog redoLog;

    /**
     * The ids of the uncommitted transactions. Set of Integer objects.
     */
    private final Set losers = new HashSet();

    /**
     * Creates a new Recovery instance.
     *
     * @param index the MultiIndex to recover.
     * @param redoLog the redo redoLog.
     */
    private Recovery(MultiIndex index, RedoLog redoLog) {
        this.index = index;
        this.redoLog = redoLog;
    }

    /**
     * Runs a recovery on <code>index</code> if <code>redoLog</code> contains
     * log entries.
     * <p/>
     * If recovery succeeds the <code>index</code> is flushed and the redo log
     * is cleared. That is, the <code>index</code> is stable.<br/>
     * If recovery fails an IOException is thrown, and the redo log will not
     * be modified. The recovery process can then be executed again, after
     * fixing the cause of the IOException (e.g. disk full).
     *
     * @param index the index to recover.
     * @param redoLog the redo log.
     * @throws IOException if the recovery fails.
     */
    static void run(MultiIndex index, RedoLog redoLog) throws IOException {
        if (!redoLog.hasEntries()) {
            log.debug("RedoLog is empty, no recovery needed.");
            return;
        }
        log.info("Found uncommitted redo log. Applying changes now...");
        Recovery r = new Recovery(index, redoLog);
        r.run();
        log.info("Redo changes applied.");
    }

    /**
     * Runs the recovery process.
     *
     * @throws IOException if the recovery fails.
     */
    private void run() throws IOException {
        List actions = redoLog.getActions();

        // find loser transactions
        for (Iterator it = actions.iterator(); it.hasNext(); ) {
            MultiIndex.Action a = (MultiIndex.Action) it.next();
            if (a.getType() == MultiIndex.Action.TYPE_START) {
                losers.add(new Long(a.getTransactionId()));
            } else if (a.getType() == MultiIndex.Action.TYPE_COMMIT) {
                losers.remove(new Long(a.getTransactionId()));
            }
        }

        // find last volatile commit without changes from a loser
        int lastSafeVolatileCommit = -1;
        Set transactionIds = new HashSet();
        for (int i = 0; i < actions.size(); i++) {
            MultiIndex.Action a = (MultiIndex.Action) actions.get(i);
            if (a.getType() == MultiIndex.Action.TYPE_COMMIT) {
                transactionIds.clear();
            } else if (a.getType() == MultiIndex.Action.TYPE_VOLATILE_COMMIT) {
                transactionIds.retainAll(losers);
                // check if transactionIds contains losers
                if (transactionIds.size() > 0) {
                    // found dirty volatile commit
                    break;
                } else {
                    lastSafeVolatileCommit = i;
                }
            } else {
                transactionIds.add(new Long(a.getTransactionId()));
            }
        }

        // delete dirty indexes
        for (int i = lastSafeVolatileCommit + 1; i < actions.size(); i++) {
            MultiIndex.Action a = (MultiIndex.Action) actions.get(i);
            if (a.getType() == MultiIndex.Action.TYPE_CREATE_INDEX) {
                a.undo(index);
            }
        }

        // replay actions up to last safe volatile commit
        // ignore add node actions, they are included in volatile commits
        for (int i = 0; i < actions.size() && i <= lastSafeVolatileCommit; i++) {
            MultiIndex.Action a = (MultiIndex.Action) actions.get(i);
            switch (a.getType()) {
                case MultiIndex.Action.TYPE_ADD_INDEX:
                case MultiIndex.Action.TYPE_CREATE_INDEX:
                case MultiIndex.Action.TYPE_DELETE_INDEX:
                case MultiIndex.Action.TYPE_DELETE_NODE:
                    a.execute(index);
            }
        }

        // now replay the rest until we encounter a loser transaction
        for (int i = lastSafeVolatileCommit + 1; i < actions.size(); i++) {
            MultiIndex.Action a = (MultiIndex.Action) actions.get(i);
            if (losers.contains(new Long(a.getTransactionId()))) {
                break;
            } else {
                a.execute(index);
            }
        }

        // now we are consistent again -> flush
        index.flush();
    }
}
