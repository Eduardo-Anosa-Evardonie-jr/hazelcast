/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.proxy.txn.xa;

import com.hazelcast.client.connection.nio.ClientConnection;
import com.hazelcast.client.impl.HazelcastClientInstanceImpl;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.codec.XATransactionCommitCodec;
import com.hazelcast.client.impl.protocol.codec.XATransactionCreateCodec;
import com.hazelcast.client.impl.protocol.codec.XATransactionPrepareCodec;
import com.hazelcast.client.impl.protocol.codec.XATransactionRollbackCodec;
import com.hazelcast.client.spi.impl.ClientInvocation;
import com.hazelcast.logging.ILogger;
import com.hazelcast.transaction.TransactionException;
import com.hazelcast.transaction.TransactionNotActiveException;
import com.hazelcast.transaction.impl.Transaction;
import com.hazelcast.transaction.impl.Transaction.State;
import com.hazelcast.transaction.impl.xa.SerializableXID;
import com.hazelcast.util.Clock;
import com.hazelcast.util.ExceptionUtil;

import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.transaction.impl.Transaction.State.*;

/**
 * This class does not need to be thread-safe, it is only used via XAResource
 * All visibility guarantees handled by XAResource
 */
public class XATransactionProxy {

    private final HazelcastClientInstanceImpl client;
    private final ClientConnection connection;
    private final SerializableXID xid;
    private final int timeout;
    private final ILogger logger;

    private Transaction.State state = NO_TXN;
    private volatile String txnId;
    private long startTime;

    public XATransactionProxy(HazelcastClientInstanceImpl client, ClientConnection connection, Xid xid, int timeout) {
        this.client = client;
        this.connection = connection;
        this.timeout = timeout;
        this.xid = new SerializableXID(xid.getFormatId(), xid.getGlobalTransactionId(), xid.getBranchQualifier());
        logger = client.getLoggingService().getLogger(XATransactionProxy.class);
    }

    void begin() {
        try {
            startTime = Clock.currentTimeMillis();
            ClientMessage request = XATransactionCreateCodec.encodeRequest(xid, timeout);
            ClientMessage response = invoke(request);
            txnId = XATransactionCreateCodec.decodeResponse(response).response;
            setState(ACTIVE);
        } catch (Exception e) {
            throw ExceptionUtil.rethrow(e);
        }
    }

    void prepare() {
        checkTimeout();
        try {
            if (state != ACTIVE) {
                throw new TransactionNotActiveException("Transaction is not active");
            }
            ClientMessage request = XATransactionPrepareCodec.encodeRequest(txnId);
            invoke(request);
            setState(PREPARED);
        } catch (Exception e) {
            setState(ROLLING_BACK);
            throw ExceptionUtil.rethrow(e);
        }
    }

    void commit(boolean onePhase) {
        checkTimeout();
        try {
            if (onePhase && state != ACTIVE) {
                throw new TransactionException("Transaction is not active");
            }
            if (!onePhase && state != PREPARED) {
                throw new TransactionException("Transaction is not prepared");
            }
            setState(COMMITTING);
            ClientMessage request = XATransactionCommitCodec.encodeRequest(txnId, onePhase);
            invoke(request);
            setState(COMMITTED);
        } catch (Exception e) {
            setState(COMMIT_FAILED);
            throw ExceptionUtil.rethrow(e);
        }
    }

    void rollback() {
        setState(ROLLING_BACK);
        try {
            ClientMessage request = XATransactionRollbackCodec.encodeRequest(txnId);
            invoke(request);
        } catch (Exception exception) {
            logger.warning("Exception while rolling back the transaction", exception);
        }
        setState(ROLLED_BACK);
    }

    public String getTxnId() {
        return txnId;
    }

    public Transaction.State getState() {
        return state;
    }

    private void checkTimeout() {
        long timeoutMillis = TimeUnit.SECONDS.toMillis(timeout);
        if (startTime + timeoutMillis < Clock.currentTimeMillis()) {
            ExceptionUtil.sneakyThrow(new XAException(XAException.XA_RBTIMEOUT));
        }
    }

    private ClientMessage invoke(ClientMessage request) {
        try {
            final ClientInvocation clientInvocation = new ClientInvocation(client, request, connection);
            final Future<ClientMessage> future = clientInvocation.invoke();
            return future.get();
        } catch (Exception e) {
            throw ExceptionUtil.rethrow(e);
        }
    }

    private void setState(State state) {
        logger.finest("setStateXA: " + state);
        this.state = state;
    }

    public ClientConnection getConnection() {
        return connection;
    }

    public SerializableXID getXid() {
        return xid;
    }
}
