/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.hornetq.core.client.impl;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.hornetq.api.core.HornetQBuffer;
import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.HornetQExceptionType;
import org.hornetq.api.core.Message;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.SendAcknowledgementHandler;
import org.hornetq.api.core.client.SessionFailureListener;
import org.hornetq.core.protocol.core.Channel;
import org.hornetq.core.protocol.core.CommandConfirmationHandler;
import org.hornetq.core.protocol.core.CoreRemotingConnection;
import org.hornetq.core.protocol.core.Packet;
import org.hornetq.core.protocol.core.impl.PacketImpl;
import org.hornetq.core.protocol.core.impl.wireformat.CreateQueueMessage;
import org.hornetq.core.protocol.core.impl.wireformat.CreateSessionMessage;
import org.hornetq.core.protocol.core.impl.wireformat.ReattachSessionMessage;
import org.hornetq.core.protocol.core.impl.wireformat.ReattachSessionResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.RollbackMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionAcknowledgeMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionAddMetaDataMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionAddMetaDataMessageV2;
import org.hornetq.core.protocol.core.impl.wireformat.SessionBindingQueryMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionBindingQueryResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionCloseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionConsumerFlowCreditMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionCreateConsumerMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionDeleteQueueMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionExpireMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionForceConsumerDelivery;
import org.hornetq.core.protocol.core.impl.wireformat.SessionIndividualAcknowledgeMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionQueueQueryMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionQueueQueryResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionReceiveContinuationMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionReceiveLargeMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionReceiveMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionRequestProducerCreditsMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionSendContinuationMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionSendMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionUniqueAddMetaDataMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXACommitMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAEndMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAForgetMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAGetInDoubtXidsResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAGetTimeoutResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAJoinMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAPrepareMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAResumeMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXARollbackMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXASetTimeoutMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXASetTimeoutResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAStartMessage;
import org.hornetq.core.remoting.FailureListener;
import org.hornetq.core.server.HornetQLogger;
import org.hornetq.core.server.HornetQMessageBundle;
import org.hornetq.spi.core.protocol.RemotingConnection;
import org.hornetq.spi.core.remoting.Connection;
import org.hornetq.utils.IDGenerator;
import org.hornetq.utils.SimpleIDGenerator;
import org.hornetq.utils.TokenBucketLimiterImpl;

/*
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 *
 * @author <a href="mailto:ovidiu@feodorov.com">Ovidiu Feodorov</a>
 *
 * @author <a href="mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 *
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 *
 * @author <a href="mailto:ataylor@redhat.com">Andy Taylor</a>
 *
 * @version <tt>$Revision: 3603 $</tt> $Id: ClientSessionImpl.java 3603 2008-01-21 18:49:20Z timfox $
 *
 * $Id: ClientSessionImpl.java 3603 2008-01-21 18:49:20Z timfox $
 *
 */
class ClientSessionImpl implements ClientSessionInternal, FailureListener, CommandConfirmationHandler
{

   private final Map<String, String> metadata = new HashMap<String, String>();

   private final ClientSessionFactoryInternal sessionFactory;

   private final String name;

   private final String username;

   private final String password;

   private final boolean xa;

   private final Executor executor;

   // to be sent to consumers as consumers will need a separate consumer for flow control
   private final Executor flowControlExecutor;

   private volatile CoreRemotingConnection remotingConnection;

   /** All access to producers are guarded (i.e. synchronized) on itself. */
   private final Set<ClientProducerInternal> producers = new HashSet<ClientProducerInternal>();

   // Consumers must be an ordered map so if we fail we recreate them in the same order with the same ids
   private final Map<Long, ClientConsumerInternal> consumers = new LinkedHashMap<Long, ClientConsumerInternal>();

   private volatile boolean closed;

   private final boolean autoCommitAcks;

   private final boolean preAcknowledge;

   private final boolean autoCommitSends;

   private final boolean blockOnAcknowledge;

   private final boolean autoGroup;

   private final int ackBatchSize;

   private final int consumerWindowSize;

   private final int consumerMaxRate;

   private final int confirmationWindowSize;

   private final int producerMaxRate;

   private final boolean blockOnNonDurableSend;

   private final boolean blockOnDurableSend;

   private final int minLargeMessageSize;

   private final boolean compressLargeMessages;

   private volatile int initialMessagePacketSize;

   private final boolean cacheLargeMessageClient;

   private final Channel channel;

   private final int version;

   // For testing only
   private boolean forceNotSameRM;

   private final IDGenerator idGenerator = new SimpleIDGenerator(0);

   private final ClientProducerCreditManager producerCreditManager;

   private volatile boolean started;

   private SendAcknowledgementHandler sendAckHandler;

   private volatile boolean rollbackOnly;

   private volatile boolean workDone;

   private final String groupID;

   private volatile boolean inClose;

   private volatile SimpleString defaultAddress;

   private boolean xaRetry = false;

   // Constructors ----------------------------------------------------------------------------

   public ClientSessionImpl(final ClientSessionFactoryInternal sessionFactory,
                            final String name,
                            final String username,
                            final String password,
                            final boolean xa,
                            final boolean autoCommitSends,
                            final boolean autoCommitAcks,
                            final boolean preAcknowledge,
                            final boolean blockOnAcknowledge,
                            final boolean autoGroup,
                            final int ackBatchSize,
                            final int consumerWindowSize,
                            final int consumerMaxRate,
                            final int confirmationWindowSize,
                            final int producerWindowSize,
                            final int producerMaxRate,
                            final boolean blockOnNonDurableSend,
                            final boolean blockOnDurableSend,
                            final boolean cacheLargeMessageClient,
                            final int minLargeMessageSize,
                            final boolean compressLargeMessages,
                            final int initialMessagePacketSize,
                            final String groupID,
                            final CoreRemotingConnection remotingConnection,
                            final int version,
                            final Channel channel,
                            final Executor executor,
                            final Executor flowControlExecutor) throws HornetQException
   {
      this.sessionFactory = sessionFactory;

      this.name = name;

      this.username = username;

      this.password = password;

      this.remotingConnection = remotingConnection;

      this.executor = executor;

      this.flowControlExecutor = flowControlExecutor;

      this.xa = xa;

      this.autoCommitAcks = autoCommitAcks;

      this.preAcknowledge = preAcknowledge;

      this.autoCommitSends = autoCommitSends;

      this.blockOnAcknowledge = blockOnAcknowledge;

      this.autoGroup = autoGroup;

      this.channel = channel;

      this.version = version;

      this.ackBatchSize = ackBatchSize;

      this.consumerWindowSize = consumerWindowSize;

      this.consumerMaxRate = consumerMaxRate;

      this.confirmationWindowSize = confirmationWindowSize;

      this.producerMaxRate = producerMaxRate;

      this.blockOnNonDurableSend = blockOnNonDurableSend;

      this.blockOnDurableSend = blockOnDurableSend;

      this.cacheLargeMessageClient = cacheLargeMessageClient;

      this.minLargeMessageSize = minLargeMessageSize;

      this.compressLargeMessages = compressLargeMessages;

      this.initialMessagePacketSize = initialMessagePacketSize;

      this.groupID = groupID;

      producerCreditManager = new ClientProducerCreditManagerImpl(this, producerWindowSize);
   }

   // ClientSession implementation
   // -----------------------------------------------------------------

   public Channel getChannel()
   {
      return channel;
   }

   public void createQueue(final SimpleString address, final SimpleString queueName) throws HornetQException
   {
      internalCreateQueue(address, queueName, null, false, false);
   }

   public void createQueue(final SimpleString address, final SimpleString queueName, final boolean durable) throws HornetQException
   {
      internalCreateQueue(address, queueName, null, durable, false);
   }

   public void createQueue(final String address, final String queueName, final boolean durable) throws HornetQException
   {
      createQueue(SimpleString.toSimpleString(address), SimpleString.toSimpleString(queueName), durable);
   }

   public void createQueue(final SimpleString address,
                           final SimpleString queueName,
                           final SimpleString filterString,
                           final boolean durable) throws HornetQException
   {
      internalCreateQueue(address, queueName, filterString, durable, false);
   }

   public void createQueue(final String address,
                           final String queueName,
                           final String filterString,
                           final boolean durable) throws HornetQException
   {
      createQueue(SimpleString.toSimpleString(address),
                  SimpleString.toSimpleString(queueName),
                  SimpleString.toSimpleString(filterString),
                  durable);
   }

   public void createTemporaryQueue(final SimpleString address, final SimpleString queueName) throws HornetQException
   {
      internalCreateQueue(address, queueName, null, false, true);
   }

   public void createTemporaryQueue(final String address, final String queueName) throws HornetQException
   {
      internalCreateQueue(SimpleString.toSimpleString(address),
                          SimpleString.toSimpleString(queueName),
                          null,
                          false,
                          true);
   }

   public void createTemporaryQueue(final SimpleString address, final SimpleString queueName, final SimpleString filter) throws HornetQException
   {
      internalCreateQueue(address, queueName, filter, false, true);
   }

   public void createTemporaryQueue(final String address, final String queueName, final String filter) throws HornetQException
   {
      internalCreateQueue(SimpleString.toSimpleString(address),
                          SimpleString.toSimpleString(queueName),
                          SimpleString.toSimpleString(filter),
                          false,
                          true);
   }

   public void deleteQueue(final SimpleString queueName) throws HornetQException
   {
      checkClosed();

      channel.sendBlocking(new SessionDeleteQueueMessage(queueName));
   }

   public void deleteQueue(final String queueName) throws HornetQException
   {
      deleteQueue(SimpleString.toSimpleString(queueName));
   }

   public QueueQuery queueQuery(final SimpleString queueName) throws HornetQException
   {
      checkClosed();

      SessionQueueQueryMessage request = new SessionQueueQueryMessage(queueName);

      SessionQueueQueryResponseMessage response = (SessionQueueQueryResponseMessage)channel.sendBlocking(request);

      return new QueueQueryImpl(response.isDurable(),
                                response.getConsumerCount(),
                                response.getMessageCount(),
                                response.getFilterString(),
                                response.getAddress(),
                                response.isExists());
   }

   public BindingQuery bindingQuery(final SimpleString address) throws HornetQException
   {
      checkClosed();

      SessionBindingQueryMessage request = new SessionBindingQueryMessage(address);

      SessionBindingQueryResponseMessage response = (SessionBindingQueryResponseMessage)channel.sendBlocking(request);

      return new BindingQueryImpl(response.isExists(), response.getQueueNames());
   }

   public void forceDelivery(final long consumerID, final long sequence) throws HornetQException
   {
      checkClosed();

      SessionForceConsumerDelivery request = new SessionForceConsumerDelivery(consumerID, sequence);
      channel.send(request);
   }

   public ClientConsumer createConsumer(final SimpleString queueName) throws HornetQException
   {
      return createConsumer(queueName, null, false);
   }

   public ClientConsumer createConsumer(final String queueName) throws HornetQException
   {
      return createConsumer(SimpleString.toSimpleString(queueName));
   }

   public ClientConsumer createConsumer(final SimpleString queueName, final SimpleString filterString) throws HornetQException
   {
      return createConsumer(queueName, filterString, consumerWindowSize, consumerMaxRate, false);
   }

   public void createQueue(final String address, final String queueName) throws HornetQException
   {
      internalCreateQueue(SimpleString.toSimpleString(address),
                          SimpleString.toSimpleString(queueName),
                          null,
                          true,
                          false);
   }

   public ClientConsumer createConsumer(final String queueName, final String filterString) throws HornetQException
   {
      return createConsumer(SimpleString.toSimpleString(queueName), SimpleString.toSimpleString(filterString));
   }

   public ClientConsumer createConsumer(final SimpleString queueName,
                                        final SimpleString filterString,
                                        final boolean browseOnly) throws HornetQException
   {
      return createConsumer(queueName, filterString, consumerWindowSize, consumerMaxRate, browseOnly);
   }

   public ClientConsumer createConsumer(final SimpleString queueName, final boolean browseOnly) throws HornetQException
   {
      return createConsumer(queueName, null, consumerWindowSize, consumerMaxRate, browseOnly);
   }

   public ClientConsumer createConsumer(final String queueName, final String filterString, final boolean browseOnly) throws HornetQException
   {
      return createConsumer(SimpleString.toSimpleString(queueName),
                            SimpleString.toSimpleString(filterString),
                            browseOnly);
   }

   public ClientConsumer createConsumer(final String queueName, final boolean browseOnly) throws HornetQException
   {
      return createConsumer(SimpleString.toSimpleString(queueName), null, browseOnly);
   }

   /**
    * Note, we DO NOT currently support direct consumers (i.e. consumers where delivery occurs on
    * the remoting thread).
    * <p>
    * Direct consumers have issues with blocking and failover. E.g. if direct then inside
    * MessageHandler call a blocking method like rollback or acknowledge (blocking) This can block
    * until failover completes, which disallows the thread to be used to deliver any responses to
    * the client during that period, so failover won't occur. If we want direct consumers we need to
    * rethink how they work.
    */
   public ClientConsumer createConsumer(final SimpleString queueName,
                                        final SimpleString filterString,
                                        final int windowSize,
                                        final int maxRate,
                                        final boolean browseOnly) throws HornetQException
   {
      return internalCreateConsumer(queueName, filterString, windowSize, maxRate, browseOnly);
   }

   public ClientConsumer createConsumer(final String queueName,
                                        final String filterString,
                                        final int windowSize,
                                        final int maxRate,
                                        final boolean browseOnly) throws HornetQException
   {
      return createConsumer(SimpleString.toSimpleString(queueName),
                            SimpleString.toSimpleString(filterString),
                            windowSize,
                            maxRate,
                            browseOnly);
   }

   public ClientProducer createProducer() throws HornetQException
   {
      return createProducer((SimpleString)null);
   }

   public ClientProducer createProducer(final SimpleString address) throws HornetQException
   {
      return createProducer(address, producerMaxRate);
   }

   public ClientProducer createProducer(final String address) throws HornetQException
   {
      return createProducer(SimpleString.toSimpleString(address));
   }

   public ClientProducer createProducer(final SimpleString address, final int maxRate) throws HornetQException
   {
      return internalCreateProducer(address, maxRate);
   }

   public ClientProducer createProducer(final String address, final int rate) throws HornetQException
   {
      return createProducer(SimpleString.toSimpleString(address), rate);
   }

   public XAResource getXAResource()
   {
      return this;
   }

   private void rollbackOnFailover(boolean outcomeKnown) throws HornetQException
   {
      rollback(false);

      if(outcomeKnown)
      {
         throw HornetQMessageBundle.BUNDLE.txRolledBack();
      }
      else
      {
         throw HornetQMessageBundle.BUNDLE.txOutcomeUnknown();
      }
   }

   public void commit() throws HornetQException
   {
      checkClosed();

      if (HornetQLogger.LOGGER.isTraceEnabled())
      {
         HornetQLogger.LOGGER.trace("Sending commit");
      }

      if (rollbackOnly)
      {
         rollbackOnFailover(true);
      }

      flushAcks();

      try
      {
         channel.sendBlocking(new PacketImpl(PacketImpl.SESS_COMMIT));
      }
      catch (HornetQException e)
      {
         if (e.getType() == HornetQExceptionType.UNBLOCKED)
         {
            // The call to commit was unlocked on failover, we therefore rollback the tx,
            // and throw a transaction rolled back exception instead

            rollbackOnFailover(false);
         }
         else
         {
            throw e;
         }
      }

      workDone = false;
   }

   public boolean isRollbackOnly()
   {
      return rollbackOnly;
   }

   public void rollback() throws HornetQException
   {
      rollback(false);
   }

   public void rollback(final boolean isLastMessageAsDelivered) throws HornetQException
   {
      checkClosed();

      // We do a "JMS style" rollback where the session is stopped, and the buffer is cancelled back
      // first before rolling back
      // This ensures messages are received in the same order after rollback w.r.t. to messages in the buffer
      // For core we could just do a straight rollback, it really depends if we want JMS style semantics or not...

      boolean wasStarted = started;

      if (wasStarted)
      {
         stop();
      }


      // We need to make sure we don't get any inflight messages
      for (ClientConsumerInternal consumer : cloneConsumers())
      {
         consumer.clear(true);
      }

      // Acks must be flushed here *after connection is stopped and all onmessages finished executing
      flushAcks();

      channel.sendBlocking(new RollbackMessage(isLastMessageAsDelivered));

      if (wasStarted)
      {
         start();
      }

      rollbackOnly = false;
   }

   public ClientMessage createMessage(final byte type,
                                      final boolean durable,
                                      final long expiration,
                                      final long timestamp,
                                      final byte priority)
   {
      return new ClientMessageImpl(type, durable, expiration, timestamp, priority, initialMessagePacketSize);
   }

   public ClientMessage createMessage(final byte type, final boolean durable)
   {
      return this.createMessage(type, durable, 0, System.currentTimeMillis(), (byte)4);
   }

   public ClientMessage createMessage(final boolean durable)
   {
      return this.createMessage((byte)0, durable);
   }

   public boolean isClosed()
   {
      return closed;
   }

   public boolean isAutoCommitSends()
   {
      return autoCommitSends;
   }

   public boolean isAutoCommitAcks()
   {
      return autoCommitAcks;
   }

   public boolean isBlockOnAcknowledge()
   {
      return blockOnAcknowledge;
   }

   public boolean isXA()
   {
      return xa;
   }

   public void resetIfNeeded() throws HornetQException
   {
      if (rollbackOnly)
      {
         HornetQLogger.LOGGER.resettingSessionAfterFailure();
         rollback(false);
      }
   }

   public void start() throws HornetQException
   {
      checkClosed();

      if (!started)
      {
         for (ClientConsumerInternal clientConsumerInternal : cloneConsumers())
         {
            clientConsumerInternal.start();
         }

         channel.send(new PacketImpl(PacketImpl.SESS_START));

         started = true;
      }
   }

   public void stop() throws HornetQException
   {
      stop(true);
   }

   private void stop(final boolean waitForOnMessage) throws HornetQException
   {
      checkClosed();

      if (started)
      {
         for (ClientConsumerInternal clientConsumerInternal : cloneConsumers())
         {
            clientConsumerInternal.stop(waitForOnMessage);
         }

         channel.sendBlocking(new PacketImpl(PacketImpl.SESS_STOP));

         started = false;
      }
   }

   public void addFailureListener(final SessionFailureListener listener)
   {
      sessionFactory.addFailureListener(listener);
   }

   public boolean removeFailureListener(final SessionFailureListener listener)
   {
      return sessionFactory.removeFailureListener(listener);
   }

   public int getVersion()
   {
      return version;
   }

   // ClientSessionInternal implementation
   // ------------------------------------------------------------

   public int getMinLargeMessageSize()
   {
      return minLargeMessageSize;
   }

   public boolean isCompressLargeMessages()
   {
      return compressLargeMessages;
   }

   /**
    * @return the cacheLargeMessageClient
    */
   public boolean isCacheLargeMessageClient()
   {
      return cacheLargeMessageClient;
   }

   public String getName()
   {
      return name;
   }

   /** Acknowledges all messages received by the consumer so far. */
   public void acknowledge(final long consumerID, final long messageID) throws HornetQException
   {
      // if we're pre-acknowledging then we don't need to do anything
      if (preAcknowledge)
      {
         return;
      }

      checkClosed();
      if (HornetQLogger.LOGGER.isDebugEnabled())
      {
         HornetQLogger.LOGGER.debug("client ack messageID = " + messageID);
      }
      SessionAcknowledgeMessage message = new SessionAcknowledgeMessage(consumerID, messageID, blockOnAcknowledge);

      if (blockOnAcknowledge)
      {
         channel.sendBlocking(message);
      }
      else
      {
         channel.sendBatched(message);
      }
   }

   public void individualAcknowledge(final long consumerID, final long messageID) throws HornetQException
   {
      // if we're pre-acknowledging then we don't need to do anything
      if (preAcknowledge)
      {
         return;
      }

      checkClosed();

      SessionIndividualAcknowledgeMessage message = new SessionIndividualAcknowledgeMessage(consumerID,
                                                                                            messageID,
                                                                                            blockOnAcknowledge);

      if (blockOnAcknowledge)
      {
         channel.sendBlocking(message);
      }
      else
      {
         channel.sendBatched(message);
      }
   }

   public void expire(final long consumerID, final long messageID) throws HornetQException
   {
      checkClosed();

      // We don't send expiries for pre-ack since message will already have been acked on server
      if (!preAcknowledge)
      {
         SessionExpireMessage message = new SessionExpireMessage(consumerID, messageID);

         channel.send(message);
      }
   }

   public void addConsumer(final ClientConsumerInternal consumer)
   {
      synchronized (consumers)
      {
         consumers.put(consumer.getID(), consumer);
      }
   }

   public void addProducer(final ClientProducerInternal producer)
   {
      synchronized (producers)
      {
         producers.add(producer);
      }
   }

   public void removeConsumer(final ClientConsumerInternal consumer) throws HornetQException
   {
      synchronized (consumers)
      {
         consumers.remove(consumer.getID());
      }
   }

   public void removeProducer(final ClientProducerInternal producer)
   {
      synchronized (producers)
      {
         producers.remove(producer);
      }
   }

   public void handleReceiveMessage(final long consumerID, final SessionReceiveMessage message) throws Exception
   {
      ClientConsumerInternal consumer = getConsumer(consumerID);

      if (consumer != null)
      {
         ClientMessageInternal clMessage = (ClientMessageInternal)message.getMessage();

         clMessage.setDeliveryCount(message.getDeliveryCount());

         clMessage.setFlowControlSize(message.getPacketSize());

         consumer.handleMessage(clMessage);
      }
   }

   public void handleReceiveLargeMessage(final long consumerID, final SessionReceiveLargeMessage message) throws Exception
   {
      ClientConsumerInternal consumer = getConsumer(consumerID);

      if (consumer != null)
      {
         consumer.handleLargeMessage(message);
      }
   }

   public void handleReceiveContinuation(final long consumerID, final SessionReceiveContinuationMessage continuation) throws Exception
   {
      ClientConsumerInternal consumer = getConsumer(consumerID);

      if (consumer != null)
      {
         consumer.handleLargeMessageContinuation(continuation);
      }
   }

   public void close() throws HornetQException
   {
      if (closed)
      {
         HornetQLogger.LOGGER.debug("Session was already closed, giving up now, this=" + this);
         return;
      }

      if (HornetQLogger.LOGGER.isDebugEnabled())
      {
         HornetQLogger.LOGGER.debug("Calling close on session " + this);
      }

      try
      {
         producerCreditManager.close();

         closeChildren();

         inClose = true;

         channel.sendBlocking(new SessionCloseMessage());
      }
      catch (Throwable e)
      {
         // Session close should always return without exception

         // Note - we only log at trace
         HornetQLogger.LOGGER.trace("Failed to close session", e);
      }

      doCleanup(false);
   }

   public synchronized void cleanUp(boolean failingOver) throws Exception
   {
      if (closed)
      {
         return;
      }

      producerCreditManager.close();

      cleanUpChildren();

      doCleanup(failingOver);
   }

   public void setSendAcknowledgementHandler(final SendAcknowledgementHandler handler)
   {
      channel.setCommandConfirmationHandler(this);

      sendAckHandler = handler;
   }

   public void preHandleFailover(CoreRemotingConnection connection)
   {
      // We lock the channel to prevent any packets to be added to the resend
      // cache during the failover process
      //we also do this before the connection fails over to give the session a chance to block for failover
      channel.lock();
   }

   // Needs to be synchronized to prevent issues with occurring concurrently with close()

   public void handleFailover(final CoreRemotingConnection backupConnection)
   {
      synchronized (this)
      {
         if (closed)
         {
            return;
         }

         boolean resetCreditManager = false;

         try
         {
            channel.transferConnection(backupConnection);

            backupConnection.syncIDGeneratorSequence(remotingConnection.getIDGeneratorSequence());

            remotingConnection = backupConnection;

            int lcid = channel.getLastConfirmedCommandID();

            Packet request = new ReattachSessionMessage(name, lcid);

            Channel channel1 = backupConnection.getChannel(1, -1);

            ReattachSessionResponseMessage response = (ReattachSessionResponseMessage)channel1.sendBlocking(request);

            if (response.isReattached())
            {
               if (HornetQLogger.LOGGER.isDebugEnabled())
               {
                  HornetQLogger.LOGGER.debug("ClientSession reattached fine, replaying commands");
               }
               // The session was found on the server - we reattached transparently ok

               channel.replayCommands(response.getLastConfirmedCommandID());
            }
            else
            {

               if (HornetQLogger.LOGGER.isDebugEnabled())
               {
                  HornetQLogger.LOGGER.debug("ClientSession couldn't be reattached, creating a new session");
               }

               // The session wasn't found on the server - probably we're failing over onto a backup server where the
               // session won't exist or the target server has been restarted - in this case the session will need to be
               // recreated,
               // and we'll need to recreate any consumers

               // It could also be that the server hasn't been restarted, but the session is currently executing close,
               // and
               // that
               // has already been executed on the server, that's why we can't find the session- in this case we *don't*
               // want
               // to recreate the session, we just want to unblock the blocking call
               if (!inClose)
               {
                  Packet createRequest = new CreateSessionMessage(name,
                                                                  channel.getID(),
                                                                  version,
                                                                  username,
                                                                  password,
                                                                  minLargeMessageSize,
                                                                  xa,
                                                                  autoCommitSends,
                                                                  autoCommitAcks,
                                                                  preAcknowledge,
                                                                  confirmationWindowSize,
                                                                  defaultAddress == null ? null
                                                                                        : defaultAddress.toString());
                  boolean retry = false;
                  do
                  {
                     try
                     {
                        channel1.sendBlocking(createRequest);
                        retry = false;
                     }
                     catch (HornetQException e)
                     {
                        // the session was created while its server was starting, retry it:
                        if (e.getType() == HornetQExceptionType.SESSION_CREATION_REJECTED)
                        {
                           HornetQLogger.LOGGER.retryCreateSessionSeverStarting(name);
                           retry = true;
                           // sleep a little bit to avoid spinning too much
                           Thread.sleep(10);
                        }
                        else
                        {
                           throw e;
                        }
                     }
                  }
                  while (retry && !inClose);

                  channel.clearCommands();

                  for (Map.Entry<Long, ClientConsumerInternal> entry : consumers.entrySet())
                  {
                     SessionQueueQueryResponseMessage queueInfo = entry.getValue().getQueueInfo();

                     // We try and recreate any non durable queues, since they probably won't be there unless
                     // they are defined in hornetq-configuration.xml
                     // This allows e.g. JMS non durable subs and temporary queues to continue to be used after failover
                     if (!queueInfo.isDurable())
                     {
                        CreateQueueMessage createQueueRequest = new CreateQueueMessage(queueInfo.getAddress(),
                                                                                       queueInfo.getName(),
                                                                                       queueInfo.getFilterString(),
                                                                                       false,
                                                                                       queueInfo.isTemporary(),
                                                                                       false);

                        sendPacketWithoutLock(createQueueRequest);
                     }

                     SessionCreateConsumerMessage createConsumerRequest = new SessionCreateConsumerMessage(entry.getKey(),
                                                                                                           entry.getValue()
                                                                                                                .getQueueName(),
                                                                                                           entry.getValue()
                                                                                                                .getFilterString(),
                                                                                                           entry.getValue()
                                                                                                                .isBrowseOnly(),
                                                                                                           false);

                     sendPacketWithoutLock(createConsumerRequest);

                     int clientWindowSize = entry.getValue().getClientWindowSize();

                     if (clientWindowSize != 0)
                     {
                        SessionConsumerFlowCreditMessage packet = new SessionConsumerFlowCreditMessage(entry.getKey(),
                                                                                                       clientWindowSize);

                        sendPacketWithoutLock(packet);
                     }
                     else
                     {
                        // https://jira.jboss.org/browse/HORNETQ-522
                        SessionConsumerFlowCreditMessage packet = new SessionConsumerFlowCreditMessage(entry.getKey(),
                                                                                                       1);
                        sendPacketWithoutLock(packet);
                     }
                  }

                  if ((!autoCommitAcks || !autoCommitSends) && workDone)
                  {
                     // Session is transacted - set for rollback only
                     // FIXME - there is a race condition here - a commit could sneak in before this is set
                     rollbackOnly = true;
                  }

                  // Now start the session if it was already started
                  if (started)
                  {
                     for (ClientConsumerInternal consumer : cloneConsumers())
                     {
                        consumer.clearAtFailover();
                        consumer.start();
                     }

                     Packet packet = new PacketImpl(PacketImpl.SESS_START);

                     packet.setChannelID(channel.getID());

                     Connection conn = channel.getConnection().getTransportConnection();

                     HornetQBuffer buffer = packet.encode(channel.getConnection());

                     conn.write(buffer, false, false);
                  }

                  resetCreditManager = true;
               }

               channel.returnBlocking();
            }
         }
         catch (Throwable t)
         {
            HornetQLogger.LOGGER.failedToHandleFailover(t);
         }
         finally
         {
            channel.setTransferring(false);
            channel.unlock();
         }

         if (resetCreditManager)
         {
            producerCreditManager.reset();

            // Also need to send more credits for consumers, otherwise the system could hand with the server
            // not having any credits to send
         }
      }

      HashMap<String, String> metaDataToSend;

      synchronized (metadata)
      {
         metaDataToSend = new HashMap<String, String>(metadata);
      }

      // Resetting the metadata after failover
      for (Map.Entry<String, String> entries : metaDataToSend.entrySet())
      {
         sendPacketWithoutLock(new SessionAddMetaDataMessageV2(entries.getKey(), entries.getValue(), false));
      }
 }

   public void addMetaDataV1(String key, String data) throws HornetQException
   {
      synchronized (metadata)
      {
         metadata.put(key, data);
      }
      channel.sendBlocking(new SessionAddMetaDataMessage(key, data));
   }

   public void addMetaData(String key, String data) throws HornetQException
   {
      synchronized (metadata)
      {
         metadata.put(key, data);
      }
      channel.sendBlocking(new SessionAddMetaDataMessageV2(key, data));
   }

   public void addUniqueMetaData(String key, String data) throws HornetQException
   {
      channel.sendBlocking(new SessionUniqueAddMetaDataMessage(key, data));
   }

   public ClientSessionFactoryInternal getSessionFactory()
   {
      return sessionFactory;
   }

   public void setAddress(final Message message, final SimpleString address)
   {
      if (defaultAddress == null)
      {
         defaultAddress = address;

         message.setAddress(address);
      }
      else
      {
         if (!address.equals(defaultAddress))
         {
            message.setAddress(address);
         }
         else
         {
            message.setAddress(null);
         }
      }
   }

   public void setPacketSize(final int packetSize)
   {
      if (packetSize > this.initialMessagePacketSize)
      {
         this.initialMessagePacketSize = (int)(packetSize * 1.2);
      }
   }

   private void sendPacketWithoutLock(final Packet packet)
   {
      packet.setChannelID(channel.getID());

      Connection conn = channel.getConnection().getTransportConnection();

      HornetQBuffer buffer = packet.encode(channel.getConnection());

      conn.write(buffer, false, false);
   }

   public void workDone()
   {
      workDone = true;
   }

   public void returnBlocking()
   {
      channel.returnBlocking();
   }

   public void sendProducerCreditsMessage(final int credits, final SimpleString address)
   {
      channel.send(new SessionRequestProducerCreditsMessage(credits, address));
   }

   public ClientProducerCredits getCredits(final SimpleString address, final boolean anon)
   {
      return producerCreditManager.getCredits(address, anon);
   }

   public void returnCredits(final SimpleString address)
   {
      producerCreditManager.returnCredits(address);
   }

   public void handleReceiveProducerCredits(final SimpleString address, final int credits)
   {
      producerCreditManager.receiveCredits(address, credits);
   }

   public ClientProducerCreditManager getProducerCreditManager()
   {
      return producerCreditManager;
   }

   // CommandConfirmationHandler implementation ------------------------------------

   public void commandConfirmed(final Packet packet)
   {
      if (packet.getType() == PacketImpl.SESS_SEND)
      {
         SessionSendMessage ssm = (SessionSendMessage)packet;

         sendAckHandler.sendAcknowledged(ssm.getMessage());
      }
      else if (packet.getType() == PacketImpl.SESS_SEND_CONTINUATION)
      {
         SessionSendContinuationMessage scm = (SessionSendContinuationMessage) packet;
         if (!scm.isContinues())
         {
            sendAckHandler.sendAcknowledged(scm.getMessage());
         }
      }

   }

   // XAResource implementation
   // --------------------------------------------------------------------

   public void commit(final Xid xid, final boolean onePhase) throws XAException
   {
      checkXA();

      // we should never throw rollback if we have already prepared
      if (rollbackOnly)
      {
         HornetQLogger.LOGGER.commitAfterFailover();
      }

      // Note - don't need to flush acks since the previous end would have
      // done this

      SessionXACommitMessage packet = new SessionXACommitMessage(xid, onePhase);

      try
      {
         SessionXAResponseMessage response = (SessionXAResponseMessage)channel.sendBlocking(packet);

         workDone = false;

         if (response.isError())
         {
            // if we retry and its not there the assume that it was committed
            if (xaRetry && response.getResponseCode() == XAException.XAER_NOTA)
            {
               return;
            }
            throw new XAException(response.getResponseCode());
         }
      }
      catch (HornetQException e)
      {
         HornetQLogger.LOGGER.failoverDuringCommit();

         // Unblocked on failover
         xaRetry = true;
         // Any error on commit -> RETRY
         // We can't rollback a Prepared TX for definition
         throw new XAException(XAException.XA_RETRY);
      }
   }

   public void end(final Xid xid, final int flags) throws XAException
   {
      checkXA();

      if (rollbackOnly)
      {
         throw new XAException(XAException.XA_RBOTHER);
      }

      try
      {
         Packet packet;

         if (flags == XAResource.TMSUSPEND)
         {
            packet = new PacketImpl(PacketImpl.SESS_XA_SUSPEND);
         }
         else if (flags == XAResource.TMSUCCESS)
         {
            packet = new SessionXAEndMessage(xid, false);
         }
         else if (flags == XAResource.TMFAIL)
         {
            packet = new SessionXAEndMessage(xid, true);
         }
         else
         {
            throw new XAException(XAException.XAER_INVAL);
         }

         flushAcks();

         SessionXAResponseMessage response = (SessionXAResponseMessage)channel.sendBlocking(packet);

         if (response.isError())
         {
            throw new XAException(response.getResponseCode());
         }
      }
      catch (HornetQException e)
      {
         HornetQLogger.LOGGER.errorCallingEnd(e);
         // This should never occur
         throw new XAException(XAException.XAER_RMERR);
      }
   }

   public void forget(final Xid xid) throws XAException
   {
      checkXA();
      try
      {
         SessionXAResponseMessage response = (SessionXAResponseMessage)channel.sendBlocking(new SessionXAForgetMessage(xid));

         if (response.isError())
         {
            throw new XAException(response.getResponseCode());
         }
      }
      catch (HornetQException e)
      {
         // This should never occur
         throw new XAException(XAException.XAER_RMERR);
      }
   }

   public int getTransactionTimeout() throws XAException
   {
      checkXA();

      try
      {
         SessionXAGetTimeoutResponseMessage response = (SessionXAGetTimeoutResponseMessage)channel.sendBlocking(new PacketImpl(PacketImpl.SESS_XA_GET_TIMEOUT));

         return response.getTimeoutSeconds();
      }
      catch (HornetQException e)
      {
         // This should never occur
         throw new XAException(XAException.XAER_RMERR);
      }
   }

   public boolean isSameRM(final XAResource xares) throws XAException
   {
      checkXA();

      if (!(xares instanceof ClientSessionInternal))
      {
         return false;
      }

      if (forceNotSameRM)
      {
         return false;
      }

      ClientSessionInternal other = (ClientSessionInternal)xares;

      return sessionFactory == other.getSessionFactory();
   }

   public int prepare(final Xid xid) throws XAException
   {
      checkXA();

      if (rollbackOnly)
      {
         throw new XAException(XAException.XA_RBOTHER);
      }

      // Note - don't need to flush acks since the previous end would have
      // done this

      SessionXAPrepareMessage packet = new SessionXAPrepareMessage(xid);

      try
      {
         SessionXAResponseMessage response = (SessionXAResponseMessage)channel.sendBlocking(packet);

         if (response.isError())
         {
            throw new XAException(response.getResponseCode());
         }
         else
         {
            xaRetry = false;
            return response.getResponseCode();
         }
      }
      catch (HornetQException e)
      {
         if (e.getType() == HornetQExceptionType.UNBLOCKED)
         {
            // Unblocked on failover
            try
            {
               HornetQLogger.LOGGER.failoverDuringPrepare();
               SessionXAResponseMessage response = (SessionXAResponseMessage)channel.sendBlocking(packet);

               if (response.isError())
               {
                  throw new XAException(response.getResponseCode());
               }
               else
               {
                  xaRetry = false;
                  return response.getResponseCode();
               }
            }
            catch (HornetQException e1)
            {
               // ignore and rollback
            }
            HornetQLogger.LOGGER.failoverDuringPrepareRollingBack();
            try
            {
               rollback(false);
            }
            catch (HornetQException e2)
            {
               throw new XAException(XAException.XAER_RMERR);
            }

            HornetQLogger.LOGGER.errorDuringPrepare(e);

            throw new XAException(XAException.XA_RBOTHER);
         }

         HornetQLogger.LOGGER.errorDuringPrepare(e);

         // This should never occur
         throw new XAException(XAException.XAER_RMERR);
      }
   }

   public Xid[] recover(final int flags) throws XAException
   {
      checkXA();

      if ((flags & XAResource.TMSTARTRSCAN) == XAResource.TMSTARTRSCAN)
      {
         try
         {
            SessionXAGetInDoubtXidsResponseMessage response = (SessionXAGetInDoubtXidsResponseMessage)channel.sendBlocking(new PacketImpl(PacketImpl.SESS_XA_INDOUBT_XIDS));

            List<Xid> xids = response.getXids();

            Xid[] xidArray = xids.toArray(new Xid[xids.size()]);

            return xidArray;
         }
         catch (HornetQException e)
         {
            // This should never occur
            throw new XAException(XAException.XAER_RMERR);
         }
      }
      else
      {
         return new Xid[0];
      }
   }

   public void rollback(final Xid xid) throws XAException
   {
      checkXA();

      try
      {
         boolean wasStarted = started;

         if (wasStarted)
         {
            stop(false);
         }

         // We need to make sure we don't get any inflight messages
         for (ClientConsumerInternal consumer : cloneConsumers())
         {
            consumer.clear(false);
         }

         flushAcks();

         SessionXARollbackMessage packet = new SessionXARollbackMessage(xid);

         SessionXAResponseMessage response = (SessionXAResponseMessage)channel.sendBlocking(packet);

         if (wasStarted)
         {
            start();
         }

         workDone = false;

         if (response.isError())
         {
            // if we retry and its not there the assume that it was rolled back
            if (xaRetry && response.getResponseCode() == XAException.XAER_NOTA)
            {
               return;
            }
            throw new XAException(response.getResponseCode());
         }
      }
      catch (HornetQException e)
      {
         if (e.getType() == HornetQExceptionType.UNBLOCKED)
         {
            // Unblocked on failover
            xaRetry = true;
            throw new XAException(XAException.XA_RETRY);
         }
         // This should never occur
         throw new XAException(XAException.XAER_RMERR);
      }
   }

   public boolean setTransactionTimeout(final int seconds) throws XAException
   {
      checkXA();

      try
      {
         SessionXASetTimeoutResponseMessage response = (SessionXASetTimeoutResponseMessage)channel.sendBlocking(new SessionXASetTimeoutMessage(seconds));

         return response.isOK();
      }
      catch (HornetQException e)
      {
         // This should never occur
         throw new XAException(XAException.XAER_RMERR);
      }
   }

   public void start(final Xid xid, final int flags) throws XAException
   {
      checkXA();

      Packet packet = null;

      try
      {
         if (flags == XAResource.TMJOIN)
         {
            packet = new SessionXAJoinMessage(xid);
         }
         else if (flags == XAResource.TMRESUME)
         {
            packet = new SessionXAResumeMessage(xid);
         }
         else if (flags == XAResource.TMNOFLAGS)
         {
            // Don't need to flush since the previous end will have done this
            packet = new SessionXAStartMessage(xid);
         }
         else
         {
            throw new XAException(XAException.XAER_INVAL);
         }

         SessionXAResponseMessage response = (SessionXAResponseMessage)channel.sendBlocking(packet);

         if (response.isError())
         {
            HornetQLogger.LOGGER.errorCallingStart(response.getMessage(), response.getResponseCode());
            throw new XAException(response.getResponseCode());
         }
      }
      catch (HornetQException e)
      {
         // we can retry this only because we know for sure that no work would have been done
         if (e.getType() == HornetQExceptionType.UNBLOCKED)
         {
            try
            {
               SessionXAResponseMessage response = (SessionXAResponseMessage)channel.sendBlocking(packet);

               if (response.isError())
               {
                  HornetQLogger.LOGGER.errorCallingStart(response.getMessage(), response.getResponseCode());
                  throw new XAException(response.getResponseCode());
               }
            }
            catch (HornetQException e1)
            {
               // This should never occur
               throw new XAException(XAException.XAER_RMERR);
            }
         }
         // This should never occur
         throw new XAException(XAException.XAER_RMERR);
      }
   }

   // FailureListener implementation --------------------------------------------

   public void connectionFailed(final HornetQException me, boolean failedOver)
   {
      try
      {
         cleanUp(false);
      }
      catch (Exception e)
      {
         HornetQLogger.LOGGER.failedToCleanupSession(e);
      }
   }

   // Public
   // ----------------------------------------------------------------------------

   public void setForceNotSameRM(final boolean force)
   {
      forceNotSameRM = force;
   }

   public RemotingConnection getConnection()
   {
      return remotingConnection;
   }

   /* (non-Javadoc)
    * @see java.lang.Object#toString()
    */
   @Override
   public String toString()
   {
      StringBuffer buffer = new StringBuffer();
      synchronized (metadata)
      {
         for (Map.Entry<String, String> entry : metadata.entrySet())
         {
            buffer.append(entry.getKey() + "=" + entry.getValue() + ",");
         }
      }

      return "ClientSessionImpl [name=" + name +
             ", username=" +
             username +
             ", closed=" +
             closed +
             ", factory = " + this.sessionFactory +
             ", metaData=(" +
             buffer +
             ")]@" +
             Integer.toHexString(hashCode());
   }

   // Protected
   // ----------------------------------------------------------------------------

   // Package Private
   // ----------------------------------------------------------------------------

   // Private
   // ----------------------------------------------------------------------------

   private int calcWindowSize(final int windowSize)
   {
      int clientWindowSize;
      if (windowSize == -1)
      {
         // No flow control - buffer can increase without bound! Only use with
         // caution for very fast consumers
         clientWindowSize = -1;
      }
      else if (windowSize == 0)
      {
         // Slow consumer - no buffering
         clientWindowSize = 0;
      }
      else if (windowSize == 1)
      {
         // Slow consumer = buffer 1
         clientWindowSize = 1;
      }
      else if (windowSize > 1)
      {
         // Client window size is half server window size
         clientWindowSize = windowSize >> 1;
      }
      else
      {
         throw new IllegalArgumentException("Invalid window size " + windowSize);
      }

      return clientWindowSize;
   }

   /**
    * @param queueName
    * @param filterString
    * @param windowSize
    * @param browseOnly
    * @return
    * @throws HornetQException
    */
   private ClientConsumer internalCreateConsumer(final SimpleString queueName,
                                                 final SimpleString filterString,
                                                 final int windowSize,
                                                 final int maxRate,
                                                 final boolean browseOnly) throws HornetQException
   {
      checkClosed();

      long consumerID = idGenerator.generateID();

      SessionCreateConsumerMessage request = new SessionCreateConsumerMessage(consumerID,
                                                                              queueName,
                                                                              filterString,
                                                                              browseOnly,
                                                                              true);

      SessionQueueQueryResponseMessage queueInfo = (SessionQueueQueryResponseMessage)channel.sendBlocking(request);

      // The actual windows size that gets used is determined by the user since
      // could be overridden on the queue settings
      // The value we send is just a hint

      int clientWindowSize = calcWindowSize(windowSize);

      ClientConsumerInternal consumer = new ClientConsumerImpl(this,
                                                               consumerID,
                                                               queueName,
                                                               filterString,
                                                               browseOnly,
                                                               clientWindowSize,
                                                               ackBatchSize,
 consumerMaxRate > 0 ? new TokenBucketLimiterImpl(maxRate)
                                                                                  : null,
                                                               executor,
                                                               flowControlExecutor,
                                                               channel,
                                                               queueInfo,
                                                               lookupTCCL());

      addConsumer(consumer);

      // Now we send window size credits to start the consumption
      // We even send it if windowSize == -1, since we need to start the
      // consumer

      if (windowSize != 0)
      {
         channel.send(new SessionConsumerFlowCreditMessage(consumerID, windowSize));
      }

      return consumer;
   }

   private ClientProducer internalCreateProducer(final SimpleString address, final int maxRate) throws HornetQException
   {
      checkClosed();

      ClientProducerInternal producer = new ClientProducerImpl(this,
                                                               address,
                                                               maxRate == -1 ? null
 : new TokenBucketLimiterImpl(maxRate),
                                                               autoCommitSends && blockOnNonDurableSend,
                                                               autoCommitSends && blockOnDurableSend,
                                                               autoGroup,
                                                               groupID == null ? null : new SimpleString(groupID),
                                                               minLargeMessageSize,
                                                               channel);

      addProducer(producer);

      return producer;
   }

   private void internalCreateQueue(final SimpleString address,
                                    final SimpleString queueName,
                                    final SimpleString filterString,
                                    final boolean durable,
                                    final boolean temp) throws HornetQException
   {
      checkClosed();

      if (durable && temp)
      {
         throw HornetQMessageBundle.BUNDLE.queueMisConfigured();
      }

      CreateQueueMessage request = new CreateQueueMessage(address, queueName, filterString, durable, temp, true);

      channel.sendBlocking(request);
   }

   private void checkXA() throws XAException
   {
      if (!xa)
      {
         HornetQLogger.LOGGER.sessionNotXA();
         throw new XAException(XAException.XAER_RMERR);
      }
   }

   private void checkClosed() throws HornetQException
   {
      if (closed || inClose)
      {
         throw HornetQMessageBundle.BUNDLE.sessionClosed();
      }
   }

   private ClassLoader lookupTCCL()
   {
      return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>()
      {
         public ClassLoader run()
         {
            return Thread.currentThread().getContextClassLoader();
         }
      });

   }

   /**
    * @param consumerID
    * @return
    */
   private ClientConsumerInternal getConsumer(final long consumerID)
   {
      synchronized (consumers)
      {
         ClientConsumerInternal consumer = consumers.get(consumerID);
         return consumer;
      }
   }

   private void doCleanup(boolean failingOver)
   {
      if (remotingConnection != null)
      {
         remotingConnection.removeFailureListener(this);
      }

      if (HornetQLogger.LOGGER.isDebugEnabled())
      {
         HornetQLogger.LOGGER.debug("calling cleanup on " + this);
      }

      synchronized (this)
      {
         closed = true;

         channel.close();

         // if the server is sending a disconnect
         // any pending blocked operation could hang without this
         channel.returnBlocking();
      }

      sessionFactory.removeSession(this, failingOver);
   }

   private void cleanUpChildren() throws Exception
   {
      Set<ClientConsumerInternal> consumersClone = cloneConsumers();

      for (ClientConsumerInternal consumer : consumersClone)
      {
         consumer.cleanUp();
      }

      Set<ClientProducerInternal> producersClone = cloneProducers();

      for (ClientProducerInternal producer : producersClone)
      {
         producer.cleanUp();
      }
   }

   /**
    * @return
    */
   private Set<ClientProducerInternal> cloneProducers()
   {
      Set<ClientProducerInternal> producersClone;

      synchronized (producers)
      {
         producersClone = new HashSet<ClientProducerInternal>(producers);
      }
      return producersClone;
   }

   /**
    * @return
    */
   private Set<ClientConsumerInternal> cloneConsumers()
   {
      synchronized (consumers)
      {
         return new HashSet<ClientConsumerInternal>(consumers.values());
      }
   }

   private void closeChildren() throws HornetQException
   {
      Set<ClientConsumerInternal> consumersClone = cloneConsumers();

      for (ClientConsumer consumer : consumersClone)
      {
         consumer.close();
      }

      Set<ClientProducerInternal> producersClone = cloneProducers();

      for (ClientProducer producer : producersClone)
      {
         producer.close();
      }
   }

   private void flushAcks() throws HornetQException
   {
      for (ClientConsumerInternal consumer : cloneConsumers())
      {
         consumer.flushAcks();
      }
   }

   private static class BindingQueryImpl implements BindingQuery
   {

      private final boolean exists;

      private final ArrayList<SimpleString> queueNames;

      public BindingQueryImpl(final boolean exists, final List<SimpleString> queueNames)
      {
         this.exists = exists;
         this.queueNames = new ArrayList<SimpleString>(queueNames);
      }

      public List<SimpleString> getQueueNames()
      {
         return queueNames;
      }

      public boolean isExists()
      {
         return exists;
      }
   }

   private static class QueueQueryImpl implements QueueQuery
   {

      private final boolean exists;

      private final boolean durable;

      private final long messageCount;

      private final SimpleString filterString;

      private final int consumerCount;

      private final SimpleString address;

      public QueueQueryImpl(final boolean durable,
                            final int consumerCount,
                            final long messageCount,
                            final SimpleString filterString,
                            final SimpleString address,
                            final boolean exists)
      {

         this.durable = durable;
         this.consumerCount = consumerCount;
         this.messageCount = messageCount;
         this.filterString = filterString;
         this.address = address;
         this.exists = exists;
      }

      public SimpleString getAddress()
      {
         return address;
      }

      public int getConsumerCount()
      {
         return consumerCount;
      }

      public SimpleString getFilterString()
      {
         return filterString;
      }

      public long getMessageCount()
      {
         return messageCount;
      }

      public boolean isDurable()
      {
         return durable;
      }

      public boolean isExists()
      {
         return exists;
      }

   }
}
