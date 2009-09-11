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

package org.hornetq.core.server.impl;

import static org.hornetq.core.management.NotificationType.CONSUMER_CREATED;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.hornetq.core.buffers.ChannelBuffers;
import org.hornetq.core.client.impl.ClientMessageImpl;
import org.hornetq.core.client.management.impl.ManagementHelper;
import org.hornetq.core.exception.HornetQException;
import org.hornetq.core.filter.Filter;
import org.hornetq.core.filter.impl.FilterImpl;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.management.ManagementService;
import org.hornetq.core.management.Notification;
import org.hornetq.core.persistence.StorageManager;
import org.hornetq.core.postoffice.Binding;
import org.hornetq.core.postoffice.BindingType;
import org.hornetq.core.postoffice.Bindings;
import org.hornetq.core.postoffice.PostOffice;
import org.hornetq.core.postoffice.QueueBinding;
import org.hornetq.core.postoffice.impl.LocalQueueBinding;
import org.hornetq.core.remoting.Channel;
import org.hornetq.core.remoting.CloseListener;
import org.hornetq.core.remoting.FailureListener;
import org.hornetq.core.remoting.Packet;
import org.hornetq.core.remoting.RemotingConnection;
import org.hornetq.core.remoting.impl.wireformat.CreateQueueMessage;
import org.hornetq.core.remoting.impl.wireformat.HornetQExceptionMessage;
import org.hornetq.core.remoting.impl.wireformat.NullResponseMessage;
import org.hornetq.core.remoting.impl.wireformat.PacketImpl;
import org.hornetq.core.remoting.impl.wireformat.PacketsConfirmedMessage;
import org.hornetq.core.remoting.impl.wireformat.RollbackMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionAcknowledgeMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionBindingQueryMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionBindingQueryResponseMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionConsumerCloseMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionConsumerFlowCreditMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionCreateConsumerMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionDeleteQueueMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionExpiredMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionQueueQueryMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionQueueQueryResponseMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionSendContinuationMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionSendLargeMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionSendMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXACommitMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXAEndMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXAForgetMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXAGetInDoubtXidsResponseMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXAGetTimeoutResponseMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXAJoinMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXAPrepareMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXAResponseMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXAResumeMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXARollbackMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXASetTimeoutMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXASetTimeoutResponseMessage;
import org.hornetq.core.remoting.impl.wireformat.SessionXAStartMessage;
import org.hornetq.core.remoting.impl.wireformat.replication.SessionReplicateDeliveryMessage;
import org.hornetq.core.remoting.spi.HornetQBuffer;
import org.hornetq.core.security.CheckType;
import org.hornetq.core.security.SecurityStore;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.LargeServerMessage;
import org.hornetq.core.server.MessageReference;
import org.hornetq.core.server.Queue;
import org.hornetq.core.server.QueueFactory;
import org.hornetq.core.server.ServerConsumer;
import org.hornetq.core.server.ServerMessage;
import org.hornetq.core.server.ServerSession;
import org.hornetq.core.transaction.ResourceManager;
import org.hornetq.core.transaction.Transaction;
import org.hornetq.core.transaction.impl.TransactionImpl;
import org.hornetq.utils.IDGenerator;
import org.hornetq.utils.SimpleIDGenerator;
import org.hornetq.utils.SimpleString;
import org.hornetq.utils.TypedProperties;

/*
 * Session implementation 
 * 
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a> 
 * @author <a href="mailto:clebert.suconic@jboss.com">Clebert Suconic</a> 
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 * @author <a href="mailto:andy.taylor@jboss.org>Andy Taylor</a>
 */
public class ServerSessionImpl implements ServerSession, FailureListener, CloseListener
{
   // Constants -----------------------------------------------------------------------------

   private static final Logger log = Logger.getLogger(ServerSessionImpl.class);

   private static final boolean trace = log.isTraceEnabled();

   private static void trace(String message)
   {
      log.trace(message);
   }

   // Static -------------------------------------------------------------------------------

   // Attributes ----------------------------------------------------------------------------

   private volatile long id;

   // The id of the opposite channel - i.e. if this is the live then its the id on the backup, if this is the backup
   // then it's the id on the live, or -1 if non clustered
   private volatile long oppositeChannelID;

   private final String username;

   private final String password;

   private final int minLargeMessageSize;

   private final boolean autoCommitSends;

   private final boolean autoCommitAcks;

   private final boolean preAcknowledge;

   private final boolean updateDeliveries;

   private RemotingConnection remotingConnection;

   private Channel replicatingChannel;

   private final Map<Long, ServerConsumer> consumers = new ConcurrentHashMap<Long, ServerConsumer>();

   private final Executor executor;

   private Transaction tx;

   private final StorageManager storageManager;

   private final ResourceManager resourceManager;

   public final PostOffice postOffice;

   private final SecurityStore securityStore;

   private final Channel channel;

   private final ManagementService managementService;

   private volatile boolean started = false;

   private final List<Runnable> failureRunners = new ArrayList<Runnable>();

   private final IDGenerator idGenerator = new SimpleIDGenerator(0);

   private final String name;

   private final HornetQServer server;

   private final SimpleString managementAddress;

   private final QueueFactory queueFactory;

   private final SimpleString nodeID;

   private boolean backup;

   // The current currentLargeMessage being processed
   // In case of replication, currentLargeMessage should only be accessed within the replication callbacks
   private volatile LargeServerMessage currentLargeMessage;

   private ServerSessionPacketHandler handler;

   // Constructors ---------------------------------------------------------------------------------

   public ServerSessionImpl(final String name,
                            final long oppositeChannelID,
                            final String username,
                            final String password,
                            final int minLargeMessageSize,
                            final boolean autoCommitSends,
                            final boolean autoCommitAcks,
                            final boolean preAcknowledge,
                            final boolean updateDeliveries,
                            final boolean xa,
                            final RemotingConnection remotingConnection,
                            final StorageManager storageManager,
                            final PostOffice postOffice,
                            final ResourceManager resourceManager,
                            final SecurityStore securityStore,
                            final Executor executor,
                            final Channel channel,
                            final ManagementService managementService,
                            final QueueFactory queueFactory,
                            final HornetQServer server,
                            final SimpleString managementAddress,
                            final Channel replicatingChannel,
                            final boolean backup) throws Exception
   {
      this.id = channel.getID();

      this.oppositeChannelID = oppositeChannelID;

      this.username = username;

      this.password = password;

      this.minLargeMessageSize = minLargeMessageSize;

      this.autoCommitSends = autoCommitSends;

      this.autoCommitAcks = autoCommitAcks;

      this.preAcknowledge = preAcknowledge;

      this.remotingConnection = remotingConnection;

      this.storageManager = storageManager;

      this.postOffice = postOffice;

      this.resourceManager = resourceManager;

      this.securityStore = securityStore;

      this.executor = executor;

      if (!xa)
      {
         tx = new TransactionImpl(storageManager);
      }

      this.updateDeliveries = updateDeliveries;

      this.channel = channel;

      this.managementService = managementService;

      this.name = name;

      this.server = server;

      this.managementAddress = managementAddress;

      this.queueFactory = queueFactory;

      this.nodeID = server.getNodeID();

      this.replicatingChannel = replicatingChannel;

      this.backup = backup;
      
      remotingConnection.addFailureListener(this);

      remotingConnection.addCloseListener(this);
   }

   // ServerSession implementation ----------------------------------------------------------------------------

   public ServerSessionPacketHandler getHandler()
   {
      return handler;
   }

   public void setHandler(final ServerSessionPacketHandler handler)
   {
      this.handler = handler;
   }

   public String getUsername()
   {
      return username;
   }

   public String getPassword()
   {
      return password;
   }

   public int getMinLargeMessageSize()
   {
      return minLargeMessageSize;
   }

   public long getID()
   {
      return id;
   }

   public String getName()
   {
      return name;
   }

   public Object getConnectionID()
   {
      return remotingConnection.getID();
   }

   public void removeConsumer(final ServerConsumer consumer) throws Exception
   {
      if (consumers.remove(consumer.getID()) == null)
      {
         throw new IllegalStateException("Cannot find consumer with id " + consumer.getID() + " to remove");
      }
   }

   public void close() throws Exception
   {
      if (tx != null && tx.getXid() == null)
      {
         // We only rollback local txs on close, not XA tx branches

         rollback(false);
      }

      Set<ServerConsumer> consumersClone = new HashSet<ServerConsumer>(consumers.values());

      for (ServerConsumer consumer : consumersClone)
      {
         consumer.close();
      }

      consumers.clear();

      server.removeSession(name);

      if (currentLargeMessage != null)
      {
         try
         {
            currentLargeMessage.deleteFile();
         }
         catch (Throwable error)
         {
            log.error("Failed to delete large message file", error);
         }
      }

      remotingConnection.removeFailureListener(this);
   }

   public void promptDelivery(final Queue queue)
   {
      queue.deliverAsync(executor);
   }

   public void handleCreateConsumer(final SessionCreateConsumerMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleCreateConsumer(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleCreateConsumer(packet);
            }
         });
      }
   }

   public void handleCreateQueue(final CreateQueueMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleCreateQueue(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleCreateQueue(packet);
            }
         });
      }
   }

   public void handleDeleteQueue(final SessionDeleteQueueMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleDeleteQueue(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleDeleteQueue(packet);
            }
         });
      }
   }

   public void handleExecuteQueueQuery(final SessionQueueQueryMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleExecuteQueueQuery(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleExecuteQueueQuery(packet);
            }
         });
      }
   }

   public void handleExecuteBindingQuery(final SessionBindingQueryMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleExecuteBindingQuery(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleExecuteBindingQuery(packet);
            }
         });
      }
   }

   public void handleAcknowledge(final SessionAcknowledgeMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleAcknowledge(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleAcknowledge(packet);
            }
         });
      }
   }

   public void handleExpired(final SessionExpiredMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleExpired(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleExpired(packet);
            }
         });
      }
   }

   public void handleCommit(final Packet packet)
   {
      if (replicatingChannel == null)
      {
         doHandleCommit(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleCommit(packet);
            }
         });
      }
   }

   public void handleRollback(final RollbackMessage packet)
   {

      if (replicatingChannel == null)
      {
         doHandleRollback(packet);
      }
      else
      {
         final HashSet<Queue> queues = lockUsedQueues(null);

         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               try
               {
                  doHandleRollback(packet);
               }
               finally
               {
                  for (Queue queue : queues)
                  {
                     queue.unlockDelivery();
                  }
               }
            }
         });
      }
   }

   public void handleXACommit(final SessionXACommitMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleXACommit(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleXACommit(packet);
            }
         });
      }
   }

   public void handleXAEnd(final SessionXAEndMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleXAEnd(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleXAEnd(packet);
            }
         });
      }
   }

   public void handleXAForget(final SessionXAForgetMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleXAForget(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleXAForget(packet);
            }
         });
      }
   }

   public void handleXAJoin(final SessionXAJoinMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleXAJoin(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleXAJoin(packet);
            }
         });
      }
   }

   public void handleXAResume(final SessionXAResumeMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleXAResume(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleXAResume(packet);
            }
         });
      }
   }

   public void handleXARollback(final SessionXARollbackMessage packet)
   {

      if (replicatingChannel == null)
      {
         doHandleXARollback(packet);
      }
      else
      {
         final Set<Queue> queues = lockUsedQueues(packet.getXid());

         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               try
               {
                  doHandleXARollback(packet);
               }
               finally
               {
                  for (Queue queue : queues)
                  {
                     queue.unlockDelivery();
                  }
               }
            }
         });
      }
   }

   public void handleXAStart(final SessionXAStartMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleXAStart(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleXAStart(packet);
            }
         });
      }
   }

   public void handleXASuspend(final Packet packet)
   {
      if (replicatingChannel == null)
      {
         doHandleXASuspend(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleXASuspend(packet);
            }
         });
      }
   }

   public void handleXAPrepare(final SessionXAPrepareMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleXAPrepare(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleXAPrepare(packet);
            }
         });
      }
   }

   public void handleGetInDoubtXids(final Packet packet)
   {
      if (replicatingChannel == null)
      {
         doHandleGetInDoubtXids(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleGetInDoubtXids(packet);
            }
         });
      }
   }

   public void handleGetXATimeout(final Packet packet)
   {
      if (replicatingChannel == null)
      {
         doHandleGetXATimeout(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleGetXATimeout(packet);
            }
         });
      }
   }

   public void handleSetXATimeout(final SessionXASetTimeoutMessage packet)
   {
      if (replicatingChannel == null)
      {
         doHandleSetXATimeout(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doHandleSetXATimeout(packet);
            }
         });
      }
   }

   private void lockConsumers()
   {
      for (ServerConsumer consumer : consumers.values())
      {
         consumer.lock();
      }
   }

   private void unlockConsumers()
   {
      for (ServerConsumer consumer : consumers.values())
      {
         consumer.unlock();
      }
   }

   public void handleStart(final Packet packet)
   {
      if (replicatingChannel == null)
      {
         setStarted(true);

         channel.confirm(packet);
      }
      else
      {
         lockConsumers();

         try
         {
            setStarted(true);

            replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
            {
               public void run()
               {
                  // setStarted(true);

                  channel.confirm(packet);
               }
            });
         }
         finally
         {
            unlockConsumers();
         }
      }
   }

   public void handleStop(final Packet packet)
   {
      final Packet response = new NullResponseMessage();

      // We need to stop the session *before* replicating, to avoid this situation:
      // session stop,
      // stop is replicated
      // delivery occurs, local is not stopped yet
      // stop is processed on backup
      // backup is stopped
      // delivery is processed on backup
      // it's stopped so barfs and cannot process delivery

      if (replicatingChannel == null)
      {
         setStarted(false);

         channel.confirm(packet);

         channel.send(response);
      }
      else
      {
         lockConsumers();

         try
         {

            setStarted(false);

            replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
            {
               public void run()
               {
                  channel.confirm(packet);

                  channel.send(response);

               }
            });
         }
         finally
         {
            unlockConsumers();
         }
      }
   }

   public void handleFailedOver(final Packet packet)
   {
      Set<ServerConsumer> consumersClone = new HashSet<ServerConsumer>(consumers.values());

      for (ServerConsumer consumer : consumersClone)
      {
         consumer.failedOver();
      }
   }

   public void handleClose(final Packet packet)
   {

      if (replicatingChannel == null)
      {
         doHandleClose(packet);
      }
      else
      {
         final HashSet<Queue> queues = lockUsedQueues(null);

         // We need to stop the consumers first before replicating, to ensure no deliveries occur after this,
         // but we need to process the actual close() when the replication response returns, otherwise things
         // can happen like acks can come in after close

         for (ServerConsumer consumer : consumers.values())
         {
            consumer.setStarted(false);
         }

         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               try
               {
                  doHandleClose(packet);
               }
               finally
               {
                  for (Queue queue : queues)
                  {
                     queue.unlockDelivery();
                  }
               }
            }
         });
      }
   }

   public void handleCloseConsumer(final SessionConsumerCloseMessage packet)
   {
      final ServerConsumer consumer = consumers.get(packet.getConsumerID());

      consumer.setStarted(false);

      if (replicatingChannel == null)
      {
         doHandleCloseConsumer(packet, consumer);
      }
      else
      {
         final Queue queue;

         if (consumer.getCountOfPendingDeliveries() > 0)
         {
            queue = consumer.getQueue();
            queue.lockDelivery();
         }
         else
         {
            queue = null;
         }

         // We need to stop the consumer first before replicating, to ensure no deliveries occur after this,
         // but we need to process the actual close() when the replication response returns, otherwise things
         // can happen like acks can come in after close

         consumer.setStarted(false);

         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               try
               {
                  doHandleCloseConsumer(packet, consumer);
               }
               finally
               {
                  if (queue != null)
                  {
                     queue.unlockDelivery();
                  }
               }
            }
         });
      }
   }

   public void handleReceiveConsumerCredits(final SessionConsumerFlowCreditMessage packet)
   {
      if (replicatingChannel == null)
      {
         doReceiveCredits(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doReceiveCredits(packet);
            }
         });
      }
   }

   public void handleSendLargeMessage(final SessionSendLargeMessage packet)
   {
      // need to create the LargeMessage before continue
      final LargeServerMessage msg = doCreateLargeMessage(packet);

      if (msg == null)
      {
         // packet logged an error, and played with channel.returns... and nothing needs to be done now
         return;
      }

      // With a send we must make sure it is replicated to backup before being processed on live
      // or can end up with delivery being processed on backup before original send

      if (replicatingChannel == null)
      {
         if (currentLargeMessage != null)
         {
            log.warn("Replacing incomplete LargeMessage with ID=" + currentLargeMessage.getMessageID());
         }

         currentLargeMessage = msg;

         doSendLargeMessage(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               if (trace)
               {
                  trace("(Replication) SendLargeMessage, id=" + msg.getMessageID());
               }

               if (currentLargeMessage != null)
               {
                  log.warn("Replacing incomplete LargeMessage with ID=" + currentLargeMessage.getMessageID());
               }

               currentLargeMessage = msg;

               doSendLargeMessage(packet);
            }
         });
      }
   }

   public void handleSend(final SessionSendMessage packet)
   {
      if (replicatingChannel == null)
      {
         doSend(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doSend(packet);
            }
         });
      }
   }

   public void handleSendContinuations(final SessionSendContinuationMessage packet)
   {
      if (replicatingChannel == null)
      {
         doSendContinuations(packet);
      }
      else
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, new Runnable()
         {
            public void run()
            {
               doSendContinuations(packet);
            }
         });
      }
   }

   public void handleReplicatedDelivery(final SessionReplicateDeliveryMessage packet)
   {
      ServerConsumer consumer = consumers.get(packet.getConsumerID());

      if (consumer == null)
      {
         throw new IllegalStateException("Cannot handle replicated delivery, consumer is closed " + packet.getConsumerID() +
                                         " session " +
                                         id +
                                         " " +
                                         System.identityHashCode(this));
      }

      try
      {
         consumer.deliverReplicated(packet.getMessageID());
      }
      catch (Exception e)
      {
         log.error("Failed to handle replicated delivery", e);
      }
   }

   public void handlePacketsConfirmed(final PacketsConfirmedMessage packet)
   {
      if (replicatingChannel != null)
      {
         replicatingChannel.replicatePacket(packet, oppositeChannelID, null);
      }
   }

   public int transferConnection(final RemotingConnection newConnection, final int lastReceivedCommandID)
   {
      boolean wasStarted = this.started;

      if (wasStarted)
      {
         this.setStarted(false);
      }

      if (backup)
      {
         // Put the id back to the original client session id
         this.id = this.oppositeChannelID;

         this.oppositeChannelID = -1;

         backup = false;
      }

      remotingConnection.removeFailureListener(this);
      remotingConnection.removeCloseListener(this);

      // Note. We do not destroy the replicating connection here. In the case the live server has really crashed
      // then the connection will get cleaned up anyway when the server ping timeout kicks in.
      // In the case the live server is really still up, i.e. a split brain situation (or in tests), then closing
      // the replicating connection will cause the outstanding responses to be be replayed on the live server,
      // if these reach the client who then subsequently fails over, on reconnection to backup, it will have
      // received responses that the backup did not know about.

      channel.transferConnection(newConnection, this.id, replicatingChannel);

      newConnection.syncIDGeneratorSequence(remotingConnection.getIDGeneratorSequence());

      remotingConnection = newConnection;

      remotingConnection.addFailureListener(this);
      remotingConnection.addCloseListener(this);

      int serverLastReceivedCommandID = channel.getLastReceivedCommandID();

      channel.replayCommands(lastReceivedCommandID, this.id);

      if (wasStarted)
      {
         this.setStarted(true);
      }

      return serverLastReceivedCommandID;
   }

   public Channel getChannel()
   {
      return channel;
   }

   // FailureListener implementation
   // --------------------------------------------------------------------

   public void connectionFailed(final HornetQException me)
   {
      try
      {
         log.warn("Client connection failed, clearing up resources for session " + name);

         for (Runnable runner : failureRunners)
         {
            try
            {
               runner.run();
            }
            catch (Throwable t)
            {
               log.error("Failed to execute failure runner", t);
            }
         }

         handleClose(new PacketImpl(PacketImpl.SESS_CLOSE));

         log.warn("Cleared up resources for session " + name);
      }
      catch (Throwable t)
      {
         log.error("Failed to close connection " + this);
      }
   }

   public void connectionClosed()
   {
      try
      {
         for (Runnable runner : failureRunners)
         {
            try
            {
               runner.run();
            }
            catch (Throwable t)
            {
               log.error("Failed to execute failure runner", t);
            }
         }
      }
      catch (Throwable t)
      {
         log.error("Failed fire listeners " + this);
      }

   }

   // Public
   // ----------------------------------------------------------------------------

   public Transaction getTransaction()
   {
      return tx;
   }

   // Private
   // ----------------------------------------------------------------------------

   private void doHandleCloseConsumer(final SessionConsumerCloseMessage packet, final ServerConsumer consumer)
   {
      Packet response;

      try
      {
         consumer.close();

         response = new NullResponseMessage();
      }
      catch (Exception e)
      {
         log.error("Failed to close consumer", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleCreateConsumer(final SessionCreateConsumerMessage packet)
   {
      SimpleString name = packet.getQueueName();

      SimpleString filterString = packet.getFilterString();

      boolean browseOnly = packet.isBrowseOnly();

      Packet response = null;

      try
      {
         Binding binding = postOffice.getBinding(name);

         if (binding == null || binding.getType() != BindingType.LOCAL_QUEUE)
         {
            throw new HornetQException(HornetQException.QUEUE_DOES_NOT_EXIST, "Binding " + name + " does not exist");
         }

         securityStore.check(binding.getAddress(), CheckType.CONSUME, this);

         Filter filter = null;

         if (filterString != null)
         {
            filter = new FilterImpl(filterString);
         }

         Queue theQueue;

         if (browseOnly)
         {
            // We consume a copy of the queue - TODO - this is a temporary measure
            // and will disappear once we can provide a proper iterator on the queue

            theQueue = queueFactory.createQueue(-1, binding.getAddress(), name, filter, false, true);

            // There's no need for any special locking since the list method is synchronized
            List<MessageReference> refs = ((Queue)binding.getBindable()).list(filter);

            for (MessageReference ref : refs)
            {
               theQueue.addLast(ref);
            }

            binding = new LocalQueueBinding(binding.getAddress(), theQueue, nodeID);
         }
         else
         {
            theQueue = (Queue)binding.getBindable();
         }

         ServerConsumer consumer = new ServerConsumerImpl(idGenerator.generateID(),
                                                          oppositeChannelID,
                                                          this,
                                                          (QueueBinding)binding,
                                                          filter,
                                                          started,
                                                          browseOnly,
                                                          storageManager,
                                                          postOffice.getPagingManager(),
                                                          channel,
                                                          replicatingChannel,
                                                          preAcknowledge,
                                                          updateDeliveries,
                                                          executor,
                                                          managementService);

         consumers.put(consumer.getID(), consumer);

         if (!browseOnly)
         {
            TypedProperties props = new TypedProperties();

            props.putStringProperty(ManagementHelper.HDR_ADDRESS, binding.getAddress());

            props.putStringProperty(ManagementHelper.HDR_CLUSTER_NAME, binding.getClusterName());

            props.putStringProperty(ManagementHelper.HDR_ROUTING_NAME, binding.getRoutingName());

            props.putIntProperty(ManagementHelper.HDR_DISTANCE, binding.getDistance());

            props.putIntProperty(ManagementHelper.HDR_CONSUMER_COUNT, theQueue.getConsumerCount());

            if (filterString != null)
            {
               props.putStringProperty(ManagementHelper.HDR_FILTERSTRING, filterString);
            }

            Notification notification = new Notification(null, CONSUMER_CREATED, props);

            managementService.sendNotification(notification);
         }

         response = new NullResponseMessage();
      }
      catch (Exception e)
      {
         log.error("Failed to create consumer", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);

   }

   private void doHandleCreateQueue(final CreateQueueMessage packet)
   {
      SimpleString address = packet.getAddress();

      final SimpleString name = packet.getQueueName();

      SimpleString filterString = packet.getFilterString();

      boolean temporary = packet.isTemporary();

      boolean durable = packet.isDurable();

      Packet response = null;

      try
      {
         if (durable)
         {
            // make sure the user has privileges to create this queue
            securityStore.check(address, CheckType.CREATE_DURABLE_QUEUE, this);
         }
         else
         {
            securityStore.check(address, CheckType.CREATE_NON_DURABLE_QUEUE, this);
         }

         server.createQueue(address, name, filterString, durable, temporary);

         if (temporary)
         {
            // Temporary queue in core simply means the queue will be deleted if
            // the remoting connection
            // dies. It does not mean it will get deleted automatically when the
            // session is closed.
            // It is up to the user to delete the queue when finished with it

            failureRunners.add(new Runnable()
            {
               public void run()
               {
                  try
                  {                     
                     if (postOffice.getBinding(name) != null)
                     {
                        postOffice.removeBinding(name);
                     }
                  }
                  catch (Exception e)
                  {
                     log.error("Failed to remove temporary queue " + name);
                  }
               }
            });
         }

         response = new NullResponseMessage();
      }
      catch (Exception e)
      {
         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleDeleteQueue(final SessionDeleteQueueMessage packet)
   {
      SimpleString name = packet.getQueueName();

      Packet response = null;

      try
      {
         Binding binding = postOffice.getBinding(name);

         if (binding == null || binding.getType() != BindingType.LOCAL_QUEUE)
         {
            throw new HornetQException(HornetQException.QUEUE_DOES_NOT_EXIST);
         }

         server.destroyQueue(name, this);

         response = new NullResponseMessage();
      }
      catch (Exception e)
      {
         log.error("Failed to delete queue", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleExecuteQueueQuery(final SessionQueueQueryMessage packet)
   {
      SimpleString name = packet.getQueueName();

      Packet response = null;

      try
      {
         if (name == null)
         {
            throw new IllegalArgumentException("Queue name is null");
         }

         Binding binding = postOffice.getBinding(name);

         if (binding != null && binding.getType() == BindingType.LOCAL_QUEUE)
         {
            Queue queue = (Queue)binding.getBindable();

            Filter filter = queue.getFilter();

            SimpleString filterString = filter == null ? null : filter.getFilterString();
            // TODO: Remove MAX-SIZE-BYTES from SessionQueueQueryResponse.
            response = new SessionQueueQueryResponseMessage(queue.isDurable(),
                                                            queue.getConsumerCount(),
                                                            queue.getMessageCount(),
                                                            filterString,
                                                            binding.getAddress());
         }
         else
         {
            response = new SessionQueueQueryResponseMessage();
         }
      }
      catch (Exception e)
      {
         log.error("Failed to execute queue query", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleExecuteBindingQuery(final SessionBindingQueryMessage packet)
   {
      SimpleString address = packet.getAddress();

      Packet response = null;

      try
      {
         if (address == null)
         {
            throw new IllegalArgumentException("Address is null");
         }

         List<SimpleString> names = new ArrayList<SimpleString>();

         Bindings bindings = postOffice.getMatchingBindings(address);

         for (Binding binding : bindings.getBindings())
         {
            if (binding.getType() == BindingType.LOCAL_QUEUE)
            {
               names.add(binding.getUniqueName());
            }
         }

         response = new SessionBindingQueryResponseMessage(!names.isEmpty(), names);
      }
      catch (Exception e)
      {
         log.error("Failed to execute binding query", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleAcknowledge(final SessionAcknowledgeMessage packet)
   {
      Packet response = null;

      try
      {
         ServerConsumer consumer = consumers.get(packet.getConsumerID());

         consumer.acknowledge(autoCommitAcks, tx, packet.getMessageID());

         if (packet.isRequiresResponse())
         {
            response = new NullResponseMessage();
         }
      }
      catch (Exception e)
      {
         log.error("Failed to acknowledge", e);

         if (packet.isRequiresResponse())
         {
            if (e instanceof HornetQException)
            {
               response = new HornetQExceptionMessage((HornetQException)e);
            }
            else
            {
               response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
            }
         }
      }

      channel.confirm(packet);

      if (response != null)
      {
         channel.send(response);
      }
   }

   private void doHandleExpired(final SessionExpiredMessage packet)
   {
      try
      {
         MessageReference ref = consumers.get(packet.getConsumerID()).getExpired(packet.getMessageID());

         if (ref != null)
         {
            ref.getQueue().expire(ref);
         }
      }
      catch (Exception e)
      {
         log.error("Failed to acknowledge", e);
      }

      channel.confirm(packet);
   }

   private void doHandleCommit(final Packet packet)
   {
      Packet response = null;

      try
      {
         tx.commit();

         response = new NullResponseMessage();
      }
      catch (Exception e)
      {
         log.error("Failed to commit", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }
      finally
      {
         tx = new TransactionImpl(storageManager);
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleRollback(final RollbackMessage packet)
   {
      Packet response = null;

      try
      {
         rollback(packet.isConsiderLastMessageAsDelivered());

         response = new NullResponseMessage();
      }
      catch (Exception e)
      {
         log.error("Failed to rollback", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleXACommit(final SessionXACommitMessage packet)
   {
      Packet response = null;

      Xid xid = packet.getXid();

      try
      {
         if (tx != null)
         {
            final String msg = "Cannot commit, session is currently doing work in transaction " + tx.getXid();

            response = new SessionXAResponseMessage(true, XAException.XAER_PROTO, msg);
         }
         else
         {
            Transaction theTx = resourceManager.removeTransaction(xid);

            if (theTx == null)
            {
               final String msg = "Cannot find xid in resource manager: " + xid;

               response = new SessionXAResponseMessage(true, XAException.XAER_NOTA, msg);
            }
            else
            {
               if (theTx.getState() == Transaction.State.SUSPENDED)
               {
                  // Put it back
                  resourceManager.putTransaction(xid, tx);

                  response = new SessionXAResponseMessage(true,
                                                          XAException.XAER_PROTO,
                                                          "Cannot commit transaction, it is suspended " + xid);
               }
               else
               {
                  theTx.commit(packet.isOnePhase());

                  response = new SessionXAResponseMessage(false, XAResource.XA_OK, null);
               }
            }
         }
      }
      catch (Exception e)
      {
         log.error("Failed to xa commit", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleXAEnd(final SessionXAEndMessage packet)
   {
      Packet response = null;

      Xid xid = packet.getXid();

      try
      {
         if (tx != null && tx.getXid().equals(xid))
         {
            if (tx.getState() == Transaction.State.SUSPENDED)
            {
               final String msg = "Cannot end, transaction is suspended";

               response = new SessionXAResponseMessage(true, XAException.XAER_PROTO, msg);
            }
            else
            {
               tx = null;
            }
         }
         else
         {
            // It's also legal for the TM to call end for a Xid in the suspended
            // state
            // See JTA 1.1 spec 3.4.4 - state diagram
            // Although in practice TMs rarely do this.
            Transaction theTx = resourceManager.getTransaction(xid);

            if (theTx == null)
            {
               final String msg = "Cannot find suspended transaction to end " + xid;

               response = new SessionXAResponseMessage(true, XAException.XAER_NOTA, msg);
            }
            else
            {
               if (theTx.getState() != Transaction.State.SUSPENDED)
               {
                  final String msg = "Transaction is not suspended " + xid;

                  response = new SessionXAResponseMessage(true, XAException.XAER_PROTO, msg);
               }
               else
               {
                  theTx.resume();
               }
            }
         }

         if (response == null)
         {
            response = new SessionXAResponseMessage(false, XAResource.XA_OK, null);
         }
      }
      catch (Exception e)
      {
         log.error("Failed to xa end", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleXAForget(final SessionXAForgetMessage packet)
   {
      // Do nothing since we don't support heuristic commits / rollback from the
      // resource manager

      Packet response = new SessionXAResponseMessage(false, XAResource.XA_OK, null);

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleXAJoin(final SessionXAJoinMessage packet)
   {
      Packet response = null;

      Xid xid = packet.getXid();

      try
      {
         Transaction theTx = resourceManager.getTransaction(xid);

         if (theTx == null)
         {
            final String msg = "Cannot find xid in resource manager: " + xid;

            response = new SessionXAResponseMessage(true, XAException.XAER_NOTA, msg);
         }
         else
         {
            if (theTx.getState() == Transaction.State.SUSPENDED)
            {
               response = new SessionXAResponseMessage(true,
                                                       XAException.XAER_PROTO,
                                                       "Cannot join tx, it is suspended " + xid);
            }
            else
            {
               tx = theTx;

               response = new SessionXAResponseMessage(false, XAResource.XA_OK, null);
            }
         }
      }
      catch (Exception e)
      {
         log.error("Failed to xa join", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleXAResume(final SessionXAResumeMessage packet)
   {
      Packet response = null;

      Xid xid = packet.getXid();

      try
      {
         if (tx != null)
         {
            final String msg = "Cannot resume, session is currently doing work in a transaction " + tx.getXid();

            response = new SessionXAResponseMessage(true, XAException.XAER_PROTO, msg);
         }
         else
         {
            Transaction theTx = resourceManager.getTransaction(xid);

            if (theTx == null)
            {
               final String msg = "Cannot find xid in resource manager: " + xid;

               response = new SessionXAResponseMessage(true, XAException.XAER_NOTA, msg);
            }
            else
            {
               if (theTx.getState() != Transaction.State.SUSPENDED)
               {
                  response = new SessionXAResponseMessage(true,
                                                          XAException.XAER_PROTO,
                                                          "Cannot resume transaction, it is not suspended " + xid);
               }
               else
               {
                  tx = theTx;

                  tx.resume();

                  response = new SessionXAResponseMessage(false, XAResource.XA_OK, null);
               }
            }
         }
      }
      catch (Exception e)
      {
         log.error("Failed to xa resume", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleXARollback(final SessionXARollbackMessage packet)
   {
      Packet response = null;

      Xid xid = packet.getXid();

      try
      {
         if (tx != null)
         {
            final String msg = "Cannot roll back, session is currently doing work in a transaction " + tx.getXid();

            response = new SessionXAResponseMessage(true, XAException.XAER_PROTO, msg);
         }
         else
         {
            Transaction theTx = resourceManager.removeTransaction(xid);

            if (theTx == null)
            {
               final String msg = "Cannot find xid in resource manager: " + xid;

               response = new SessionXAResponseMessage(true, XAException.XAER_NOTA, msg);
            }
            else
            {
               if (theTx.getState() == Transaction.State.SUSPENDED)
               {
                  // Put it back
                  resourceManager.putTransaction(xid, tx);

                  response = new SessionXAResponseMessage(true,
                                                          XAException.XAER_PROTO,
                                                          "Cannot rollback transaction, it is suspended " + xid);
               }
               else
               {
                  doRollback(false, theTx);

                  response = new SessionXAResponseMessage(false, XAResource.XA_OK, null);
               }
            }
         }
      }
      catch (Exception e)
      {
         log.error("Failed to xa rollback", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleXAStart(final SessionXAStartMessage packet)
   {
      Packet response = null;

      Xid xid = packet.getXid();

      try
      {
         if (tx != null)
         {
            final String msg = "Cannot start, session is already doing work in a transaction " + tx.getXid();

            response = new SessionXAResponseMessage(true, XAException.XAER_PROTO, msg);
         }
         else
         {
            tx = new TransactionImpl(xid, storageManager, postOffice);

            boolean added = resourceManager.putTransaction(xid, tx);

            if (!added)
            {
               final String msg = "Cannot start, there is already a xid " + tx.getXid();

               response = new SessionXAResponseMessage(true, XAException.XAER_DUPID, msg);
            }
            else
            {
               response = new SessionXAResponseMessage(false, XAResource.XA_OK, null);
            }
         }
      }
      catch (Exception e)
      {
         log.error("Failed to xa start", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleXASuspend(final Packet packet)
   {
      Packet response = null;

      try
      {
         if (tx == null)
         {
            final String msg = "Cannot suspend, session is not doing work in a transaction ";

            response = new SessionXAResponseMessage(true, XAException.XAER_PROTO, msg);
         }
         else
         {
            if (tx.getState() == Transaction.State.SUSPENDED)
            {
               final String msg = "Cannot suspend, transaction is already suspended " + tx.getXid();

               response = new SessionXAResponseMessage(true, XAException.XAER_PROTO, msg);
            }
            else
            {
               tx.suspend();

               tx = null;

               response = new SessionXAResponseMessage(false, XAResource.XA_OK, null);
            }
         }
      }
      catch (Exception e)
      {
         log.error("Failed to xa suspend", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleXAPrepare(final SessionXAPrepareMessage packet)
   {
      Packet response = null;

      Xid xid = packet.getXid();

      try
      {
         if (tx != null)
         {
            final String msg = "Cannot commit, session is currently doing work in a transaction " + tx.getXid();

            response = new SessionXAResponseMessage(true, XAException.XAER_PROTO, msg);
         }
         else
         {
            Transaction theTx = resourceManager.getTransaction(xid);

            if (theTx == null)
            {
               final String msg = "Cannot find xid in resource manager: " + xid;

               response = new SessionXAResponseMessage(true, XAException.XAER_NOTA, msg);
            }
            else
            {
               if (theTx.getState() == Transaction.State.SUSPENDED)
               {
                  response = new SessionXAResponseMessage(true,
                                                          XAException.XAER_PROTO,
                                                          "Cannot prepare transaction, it is suspended " + xid);
               }
               else
               {
                  theTx.prepare();

                  response = new SessionXAResponseMessage(false, XAResource.XA_OK, null);
               }
            }
         }
      }
      catch (Exception e)
      {
         log.error("Failed to xa prepare", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleGetInDoubtXids(final Packet packet)
   {
      Packet response = new SessionXAGetInDoubtXidsResponseMessage(resourceManager.getPreparedTransactions());

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleGetXATimeout(final Packet packet)
   {
      Packet response = new SessionXAGetTimeoutResponseMessage(resourceManager.getTimeoutSeconds());

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleSetXATimeout(final SessionXASetTimeoutMessage packet)
   {
      Packet response = new SessionXASetTimeoutResponseMessage(resourceManager.setTimeoutSeconds(packet.getTimeoutSeconds()));

      channel.confirm(packet);

      channel.send(response);
   }

   private void doHandleClose(final Packet packet)
   {
      Packet response = null;

      try
      {
         close();

         response = new NullResponseMessage();
      }
      catch (Exception e)
      {
         log.error("Failed to close", e);

         if (e instanceof HornetQException)
         {
            response = new HornetQExceptionMessage((HornetQException)e);
         }
         else
         {
            response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
         }
      }

      channel.confirm(packet);

      // We flush the confirmations to make sure any send confirmations get handled on the client side
      channel.flushConfirmations();

      channel.send(response);

      channel.close();
   }

   private void setStarted(final boolean s)
   {
      Set<ServerConsumer> consumersClone = new HashSet<ServerConsumer>(consumers.values());

      for (ServerConsumer consumer : consumersClone)
      {
         consumer.setStarted(s);
      }

      started = s;
   }

   /**
    * We need to create the LargeMessage before replicating the packet, or else we won't know how to extract the destination,
    * which is stored on the header
    * @param packet
    * @throws Exception
    */
   private LargeServerMessage doCreateLargeMessage(final SessionSendLargeMessage packet)
   {
      try
      {
         return createLargeMessageStorage(packet.getLargeMessageHeader());
      }
      catch (Exception e)
      {
         log.error("Failed to create large message", e);
         Packet response = null;

         channel.confirm(packet);
         if (response != null)
         {
            channel.send(response);
         }
         return null;
      }
   }

   private void doReceiveCredits(final SessionConsumerFlowCreditMessage packet)
   {
      try
      {
         consumers.get(packet.getConsumerID()).receiveCredits(packet.getCredits());
      }
      catch (Exception e)
      {
         log.error("Failed to receive credits", e);
      }
      channel.confirm(packet);
   }

   private void doSendLargeMessage(final SessionSendLargeMessage packet)
   {
      try
      {
         long id = storageManager.generateUniqueID();

         currentLargeMessage.setMessageID(id);
      }
      catch (Exception e)
      {
         log.error("Failed to send message", e);
      }

      channel.confirm(packet);
   }

   private void doSend(final SessionSendMessage packet)
   {
      Packet response = null;

      try
      {
         ServerMessage message = packet.getServerMessage();

         long id = storageManager.generateUniqueID();

         message.setMessageID(id);

         if (message.getDestination().equals(managementAddress))
         {
            // It's a management message

            handleManagementMessage(message);
         }
         else
         {
            send(message);
         }

         if (packet.isRequiresResponse())
         {
            response = new NullResponseMessage();
         }
      }
      catch (Exception e)
      {
         log.error("Failed to send message", e);

         if (packet.isRequiresResponse())
         {
            if (e instanceof HornetQException)
            {
               response = new HornetQExceptionMessage((HornetQException)e);
            }
            else
            {
               response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
            }
         }
      }

      channel.confirm(packet);

      if (response != null)
      {
         channel.send(response);
      }
   }

   /**
    * @param packet
    */
   private void doSendContinuations(final SessionSendContinuationMessage packet)
   {
      Packet response = null;

      try
      {
         if (currentLargeMessage == null)
         {
            throw new HornetQException(HornetQException.ILLEGAL_STATE, "large-message not initialized on server");
         }

         currentLargeMessage.addBytes(packet.getBody());

         if (!packet.isContinues())
         {
            final LargeServerMessage message = currentLargeMessage;

            currentLargeMessage = null;

            message.complete();

            send(message);
         }

         if (packet.isRequiresResponse())
         {
            response = new NullResponseMessage();
         }
      }
      catch (Exception e)
      {
         log.error("Failed to send message", e);

         if (packet.isRequiresResponse())
         {
            if (e instanceof HornetQException)
            {
               response = new HornetQExceptionMessage((HornetQException)e);
            }
            else
            {
               response = new HornetQExceptionMessage(new HornetQException(HornetQException.INTERNAL_ERROR));
            }
         }
      }

      channel.confirm(packet);

      if (response != null)
      {
         channel.send(response);
      }
   }

   private void handleManagementMessage(final ServerMessage message) throws Exception
   {
      try
      {
         securityStore.check(message.getDestination(), CheckType.MANAGE, this);
      }
      catch (HornetQException e)
      {
         if (!autoCommitSends)
         {
            tx.markAsRollbackOnly(e);
         }
         throw e;
      }

      ServerMessage reply = managementService.handleMessage(message);

      SimpleString replyTo = (SimpleString)message.getProperty(ClientMessageImpl.REPLYTO_HEADER_NAME);

      if (replyTo != null)
      {
         reply.setDestination(replyTo);

         send(reply);
      }
   }

   private LargeServerMessage createLargeMessageStorage(final byte[] header) throws Exception
   {
      LargeServerMessage largeMessage = storageManager.createLargeMessage();

      HornetQBuffer headerBuffer = ChannelBuffers.wrappedBuffer(header);

      largeMessage.decodeProperties(headerBuffer);

      return largeMessage;
   }

   private void doRollback(final boolean lastMessageAsDelived, final Transaction theTx) throws Exception
   {
      boolean wasStarted = started;

      List<MessageReference> toCancel = new ArrayList<MessageReference>();

      for (ServerConsumer consumer : consumers.values())
      {
         if (wasStarted)
         {
            consumer.setStarted(false);
         }

         toCancel.addAll(consumer.cancelRefs(lastMessageAsDelived, theTx));
      }

      for (MessageReference ref : toCancel)
      {
         ref.getQueue().cancel(theTx, ref);
      }

      theTx.rollback();

      if (wasStarted)
      {
         for (ServerConsumer consumer : consumers.values())
         {
            consumer.setStarted(true);
         }
      }
   }

   private void rollback(boolean lastMessageAsDelived) throws Exception
   {
      if (tx == null)
      {
         // Might be null if XA

         tx = new TransactionImpl(storageManager);
      }

      doRollback(lastMessageAsDelived, tx);

      tx = new TransactionImpl(storageManager);
   }

   private void send(final ServerMessage msg) throws Exception
   {
      // check the user has write access to this address.
      try
      {
         securityStore.check(msg.getDestination(), CheckType.SEND, this);
      }
      catch (HornetQException e)
      {
         if (!autoCommitSends)
         {
            tx.markAsRollbackOnly(e);
         }
         throw e;
      }

      if (tx == null || autoCommitSends)
      {
         postOffice.route(msg);
      }
      else
      {
         postOffice.route(msg, tx);
      }
   }

   /**
    * We need to avoid delivery when rolling back while doing replication, or the backup node could be on a different order
    * @return
    */
   private HashSet<Queue> lockUsedQueues(Xid xid)
   {
      final HashSet<Queue> queues = new HashSet<Queue>();

      for (ServerConsumer consumer : consumers.values())
      {
         queues.add(consumer.getQueue());
      }

      Transaction localTX;
      if (xid == null)
      {
         localTX = tx;
      }
      else
      {
         localTX = resourceManager.getTransaction(xid);
      }

      if (localTX != null)
      {
         queues.addAll(localTX.getDistinctQueues());
      }

      for (Queue queue : queues)
      {
         queue.lockDelivery();
      }
      return queues;
   }
}