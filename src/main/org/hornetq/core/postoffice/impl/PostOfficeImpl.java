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

package org.hornetq.core.postoffice.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.hornetq.core.buffers.ChannelBuffers;
import org.hornetq.core.client.management.impl.ManagementHelper;
import org.hornetq.core.exception.HornetQException;
import org.hornetq.core.filter.Filter;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.management.ManagementService;
import org.hornetq.core.management.Notification;
import org.hornetq.core.management.NotificationListener;
import org.hornetq.core.management.NotificationType;
import org.hornetq.core.message.impl.MessageImpl;
import org.hornetq.core.paging.PageTransactionInfo;
import org.hornetq.core.paging.PagingManager;
import org.hornetq.core.paging.impl.PageTransactionInfoImpl;
import org.hornetq.core.persistence.StorageManager;
import org.hornetq.core.postoffice.AddressManager;
import org.hornetq.core.postoffice.Binding;
import org.hornetq.core.postoffice.BindingType;
import org.hornetq.core.postoffice.Bindings;
import org.hornetq.core.postoffice.DuplicateIDCache;
import org.hornetq.core.postoffice.PostOffice;
import org.hornetq.core.postoffice.QueueInfo;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.Queue;
import org.hornetq.core.server.QueueFactory;
import org.hornetq.core.server.ServerMessage;
import org.hornetq.core.server.impl.ServerMessageImpl;
import org.hornetq.core.settings.HierarchicalRepository;
import org.hornetq.core.settings.impl.AddressSettings;
import org.hornetq.core.transaction.Transaction;
import org.hornetq.core.transaction.TransactionOperation;
import org.hornetq.core.transaction.TransactionPropertyIndexes;
import org.hornetq.core.transaction.Transaction.State;
import org.hornetq.core.transaction.impl.TransactionImpl;
import org.hornetq.utils.ExecutorFactory;
import org.hornetq.utils.SimpleString;
import org.hornetq.utils.TypedProperties;
import org.hornetq.utils.UUIDGenerator;

/**
 * A PostOfficeImpl
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="jmesnil@redhat.com">Jeff Mesnil</a>
 * @author <a href="csuconic@redhat.com">Clebert Suconic</a>
 */
public class PostOfficeImpl implements PostOffice, NotificationListener
{
   private static final Logger log = Logger.getLogger(PostOfficeImpl.class);

   public static final SimpleString HDR_RESET_QUEUE_DATA = new SimpleString("_HQ_RESET_QUEUE_DATA");

   private HornetQServer server;

   private final AddressManager addressManager;

   private final QueueFactory queueFactory;

   private final StorageManager storageManager;

   private final PagingManager pagingManager;

   private volatile boolean started;

   private volatile boolean backup;

   private final ManagementService managementService;

   private final Reaper reaperRunnable = new Reaper();

   private volatile Thread reaperThread;

   private final long reaperPeriod;

   private final int reaperPriority;

   private final ConcurrentMap<SimpleString, DuplicateIDCache> duplicateIDCaches = new ConcurrentHashMap<SimpleString, DuplicateIDCache>();

   private final int idCacheSize;

   private final boolean persistIDCache;

   // Each queue has a transient ID which lasts the lifetime of its binding. This is used in clustering when routing
   // messages to particular queues on nodes. We could
   // use the queue name on the node to identify it. But sometimes we need to route to maybe 10s of thousands of queues
   // on a particular node, and all would
   // have to be specified in the message. Specify 10000 ints takes up a lot less space than 10000 arbitrary queue names
   // The drawback of this approach is we only allow up to 2^32 queues in memory at any one time
   private int transientIDSequence;

   private Set<Integer> transientIDs = new HashSet<Integer>();

   private Map<SimpleString, QueueInfo> queueInfos = new HashMap<SimpleString, QueueInfo>();

   private final Object notificationLock = new Object();

   private final org.hornetq.utils.ExecutorFactory redistributorExecutorFactory;

   private final HierarchicalRepository<AddressSettings> addressSettingsRepository;

   public PostOfficeImpl(final HornetQServer server,
                         final StorageManager storageManager,
                         final PagingManager pagingManager,
                         final QueueFactory bindableFactory,
                         final ManagementService managementService,
                         final long reaperPeriod,
                         final int reaperPriority,
                         final boolean enableWildCardRouting,
                         final boolean backup,
                         final int idCacheSize,
                         final boolean persistIDCache,
                         final ExecutorFactory orderedExecutorFactory,
                         HierarchicalRepository<AddressSettings> addressSettingsRepository)

   {
      this.server = server;

      this.storageManager = storageManager;

      this.queueFactory = bindableFactory;

      this.managementService = managementService;

      this.pagingManager = pagingManager;

      this.reaperPeriod = reaperPeriod;

      this.reaperPriority = reaperPriority;

      if (enableWildCardRouting)
      {
         addressManager = new WildcardAddressManager();
      }
      else
      {
         addressManager = new SimpleAddressManager();
      }

      this.backup = backup;

      this.idCacheSize = idCacheSize;

      this.persistIDCache = persistIDCache;

      this.redistributorExecutorFactory = orderedExecutorFactory;

      this.addressSettingsRepository = addressSettingsRepository;
   }

   // HornetQComponent implementation ---------------------------------------

   public synchronized void start() throws Exception
   {
      managementService.addNotificationListener(this);

      if (pagingManager != null)
      {
         pagingManager.setPostOffice(this);
      }

      // Injecting the postoffice (itself) on queueFactory for paging-control
      queueFactory.setPostOffice(this);

      // The flag started needs to be set before starting the Reaper Thread
      // This is to avoid thread leakages where the Reaper would run beyong the life cycle of the PostOffice
      started = true;

      if (!backup)
      {
         startExpiryScanner();
      }
   }

   public synchronized void stop() throws Exception
   {
      started = false;

      managementService.removeNotificationListener(this);

      reaperRunnable.stop();

      if (reaperThread != null)
      {
         reaperThread.join();

         reaperThread = null;
      }

      addressManager.clear();

      queueInfos.clear();

      transientIDs.clear();

   }

   public boolean isStarted()
   {
      return started;
   }

   // NotificationListener implementation -------------------------------------

   public void onNotification(final Notification notification)
   {
      synchronized (notificationLock)
      {
         NotificationType type = notification.getType();

         switch (type)
         {
            case BINDING_ADDED:
            {
               TypedProperties props = notification.getProperties();

               Integer bindingType = (Integer)props.getProperty(ManagementHelper.HDR_BINDING_TYPE);

               if (bindingType == null)
               {
                  throw new IllegalArgumentException("Binding type not specified");
               }

               if (bindingType == BindingType.DIVERT_INDEX)
               {
                  // We don't propagate diverts
                  return;
               }

               SimpleString routingName = (SimpleString)props.getProperty(ManagementHelper.HDR_ROUTING_NAME);

               SimpleString clusterName = (SimpleString)props.getProperty(ManagementHelper.HDR_CLUSTER_NAME);

               SimpleString address = (SimpleString)props.getProperty(ManagementHelper.HDR_ADDRESS);

               Integer transientID = (Integer)props.getProperty(ManagementHelper.HDR_BINDING_ID);

               SimpleString filterString = (SimpleString)props.getProperty(ManagementHelper.HDR_FILTERSTRING);

               Integer distance = (Integer)props.getProperty(ManagementHelper.HDR_DISTANCE);

               QueueInfo info = new QueueInfo(routingName, clusterName, address, filterString, transientID, distance);

               queueInfos.put(clusterName, info);

               break;
            }
            case BINDING_REMOVED:
            {
               TypedProperties props = notification.getProperties();

               SimpleString clusterName = (SimpleString)props.getProperty(ManagementHelper.HDR_CLUSTER_NAME);

               if (clusterName == null)
               {
                  throw new IllegalStateException("No cluster name");
               }

               QueueInfo info = queueInfos.remove(clusterName);

               if (info == null)
               {
                  throw new IllegalStateException("Cannot find queue info for queue " + clusterName);
               }

               break;
            }
            case CONSUMER_CREATED:
            {
               TypedProperties props = notification.getProperties();

               SimpleString clusterName = (SimpleString)props.getProperty(ManagementHelper.HDR_CLUSTER_NAME);

               if (clusterName == null)
               {
                  throw new IllegalStateException("No cluster name");
               }

               SimpleString filterString = (SimpleString)props.getProperty(ManagementHelper.HDR_FILTERSTRING);

               QueueInfo info = queueInfos.get(clusterName);

               if (info == null)
               {
                  throw new IllegalStateException("Cannot find queue info for queue " + clusterName);
               }

               info.incrementConsumers();

               if (filterString != null)
               {
                  List<SimpleString> filterStrings = info.getFilterStrings();

                  if (filterStrings == null)
                  {
                     filterStrings = new ArrayList<SimpleString>();

                     info.setFilterStrings(filterStrings);
                  }

                  filterStrings.add(filterString);
               }

               Integer distance = (Integer)props.getProperty(ManagementHelper.HDR_DISTANCE);

               if (distance == null)
               {
                  throw new IllegalStateException("No distance");
               }

               if (distance > 0)
               {
                  SimpleString queueName = (SimpleString)props.getProperty(ManagementHelper.HDR_ROUTING_NAME);

                  if (queueName == null)
                  {
                     throw new IllegalStateException("No queue name");
                  }

                  Binding binding = getBinding(queueName);

                  if (binding != null)
                  {
                     // We have a local queue
                     Queue queue = (Queue)binding.getBindable();

                     AddressSettings addressSettings = addressSettingsRepository.getMatch(binding.getAddress()
                                                                                                 .toString());

                     long redistributionDelay = addressSettings.getRedistributionDelay();

                     if (redistributionDelay != -1)
                     {
                        queue.addRedistributor(redistributionDelay,
                                               redistributorExecutorFactory.getExecutor(),
                                               server.getReplicatingChannel());
                     }
                  }
               }

               break;
            }
            case CONSUMER_CLOSED:
            {
               TypedProperties props = notification.getProperties();

               SimpleString clusterName = (SimpleString)props.getProperty(ManagementHelper.HDR_CLUSTER_NAME);

               if (clusterName == null)
               {
                  throw new IllegalStateException("No distance");
               }

               SimpleString filterString = (SimpleString)props.getProperty(ManagementHelper.HDR_FILTERSTRING);

               QueueInfo info = queueInfos.get(clusterName);

               if (info == null)
               {
                  return;
               }

               info.decrementConsumers();

               if (filterString != null)
               {
                  List<SimpleString> filterStrings = info.getFilterStrings();

                  filterStrings.remove(filterString);
               }

               if (info.getNumberOfConsumers() == 0)
               {
                  Integer distance = (Integer)props.getProperty(ManagementHelper.HDR_DISTANCE);

                  if (distance == null)
                  {
                     throw new IllegalStateException("No cluster name");
                  }

                  if (distance == 0)
                  {
                     SimpleString queueName = (SimpleString)props.getProperty(ManagementHelper.HDR_ROUTING_NAME);

                     if (queueName == null)
                     {
                        throw new IllegalStateException("No queue name");
                     }

                     Binding binding = getBinding(queueName);

                     if (binding == null)
                     {
                        throw new IllegalStateException("No queue " + queueName);
                     }

                     Queue queue = (Queue)binding.getBindable();

                     AddressSettings addressSettings = addressSettingsRepository.getMatch(binding.getAddress()
                                                                                                 .toString());

                     long redistributionDelay = addressSettings.getRedistributionDelay();

                     if (redistributionDelay != -1)
                     {
                        queue.addRedistributor(redistributionDelay,
                                               redistributorExecutorFactory.getExecutor(),
                                               server.getReplicatingChannel());
                     }
                  }
               }

               break;
            }
            default:
            {
               break;
            }
         }
      }
   }

   // PostOffice implementation -----------------------------------------------

   // TODO - needs to be synchronized to prevent happening concurrently with activate().
   // (and possible removeBinding and other methods)
   // Otherwise can have situation where createQueue comes in before failover, then failover occurs
   // and post office is activated but queue remains unactivated after failover so delivery never occurs
   // even though failover is complete
   public synchronized void addBinding(final Binding binding) throws Exception
   {
      binding.setID(generateTransientID());

      boolean existed = addressManager.addBinding(binding);

      // TODO - why is this code here?
      // Shouldn't it be in HornetQServerImpl::createQueue??
      if (binding.getType() == BindingType.LOCAL_QUEUE)
      {
         Queue queue = (Queue)binding.getBindable();

         if (backup)
         {
            queue.setBackup();
         }

         managementService.registerQueue(queue, binding.getAddress(), storageManager);

         if (!existed)
         {
            managementService.registerAddress(binding.getAddress());
         }
      }

      TypedProperties props = new TypedProperties();

      props.putIntProperty(ManagementHelper.HDR_BINDING_TYPE, binding.getType().toInt());

      props.putStringProperty(ManagementHelper.HDR_ADDRESS, binding.getAddress());

      props.putStringProperty(ManagementHelper.HDR_CLUSTER_NAME, binding.getClusterName());

      props.putStringProperty(ManagementHelper.HDR_ROUTING_NAME, binding.getRoutingName());

      props.putIntProperty(ManagementHelper.HDR_BINDING_ID, binding.getID());

      props.putIntProperty(ManagementHelper.HDR_DISTANCE, binding.getDistance());

      Filter filter = binding.getFilter();

      if (filter != null)
      {
         props.putStringProperty(ManagementHelper.HDR_FILTERSTRING, filter.getFilterString());
      }

      String uid = UUIDGenerator.getInstance().generateStringUUID();

      managementService.sendNotification(new Notification(uid, NotificationType.BINDING_ADDED, props));
   }

   public synchronized Binding removeBinding(final SimpleString uniqueName) throws Exception
   {
      Binding binding = addressManager.removeBinding(uniqueName);

      if (binding == null)
      {
         throw new HornetQException(HornetQException.QUEUE_DOES_NOT_EXIST);
      }

      if (binding.getType() == BindingType.LOCAL_QUEUE)
      {
         managementService.unregisterQueue(uniqueName, binding.getAddress());

         if (addressManager.getBindingsForRoutingAddress(binding.getAddress()) == null)
         {
            managementService.unregisterAddress(binding.getAddress());
         }
      }
      else if (binding.getType() == BindingType.DIVERT)
      {
         managementService.unregisterDivert(uniqueName);

         if (addressManager.getBindingsForRoutingAddress(binding.getAddress()) == null)
         {
            managementService.unregisterAddress(binding.getAddress());
         }
      }

      TypedProperties props = new TypedProperties();

      props.putStringProperty(ManagementHelper.HDR_ADDRESS, binding.getAddress());

      props.putStringProperty(ManagementHelper.HDR_CLUSTER_NAME, binding.getClusterName());

      props.putStringProperty(ManagementHelper.HDR_ROUTING_NAME, binding.getRoutingName());

      props.putIntProperty(ManagementHelper.HDR_DISTANCE, binding.getDistance());

      managementService.sendNotification(new Notification(null, NotificationType.BINDING_REMOVED, props));

      releaseTransientID(binding.getID());

      return binding;
   }

   public Bindings getBindingsForAddress(final SimpleString address)
   {
      Bindings bindings = addressManager.getBindingsForRoutingAddress(address);

      if (bindings == null)
      {
         bindings = new BindingsImpl();
      }

      return bindings;
   }

   public Binding getBinding(final SimpleString name)
   {
      return addressManager.getBinding(name);
   }

   public Bindings getMatchingBindings(final SimpleString address)
   {
      return addressManager.getMatchingBindings(address);
   }

   public void route(final ServerMessage message, Transaction tx) throws Exception
   {
      SimpleString address = message.getDestination();

      byte[] duplicateIDBytes = null;

      Object duplicateID = message.getProperty(MessageImpl.HDR_DUPLICATE_DETECTION_ID);

      DuplicateIDCache cache = null;

      if (duplicateID != null)
      {
         cache = getDuplicateIDCache(message.getDestination());

         if (duplicateID instanceof SimpleString)
         {
            duplicateIDBytes = ((SimpleString)duplicateID).getData();
         }
         else
         {
            duplicateIDBytes = (byte[])duplicateID;
         }

         if (cache.contains(duplicateIDBytes))
         {
            if (tx == null)
            {
               log.trace("Duplicate message detected - message will not be routed");
            }
            else
            {
               log.trace("Duplicate message detected - transaction will be rejected");

               tx.markAsRollbackOnly(null);
            }

            return;
         }
      }

      boolean startedTx = false;

      if (cache != null)
      {
         if (tx == null)
         {
            // We need to store the duplicate id atomically with the message storage, so we need to create a tx for this

            tx = new TransactionImpl(storageManager);

            startedTx = true;
         }

         cache.addToCache(duplicateIDBytes, tx);
      }

      if (tx == null)
      {
         if (pagingManager.page(message, true))
         {
            return;
         }
      }
      else
      {
         SimpleString destination = message.getDestination();

         boolean depage = tx.getProperty(TransactionPropertyIndexes.IS_DEPAGE) != null;

         if (!depage && pagingManager.isPaging(destination))
         {
            getPageOperation(tx).addMessageToPage(message);

            return;
         }
      }

      Bindings bindings = addressManager.getBindingsForRoutingAddress(address);

      if (bindings != null)
      {
         bindings.route(message, tx);
      }

      if (startedTx)
      {
         tx.commit();
      }
   }

   public void route(final ServerMessage message) throws Exception
   {
      route(message, null);
   }

   public boolean redistribute(final ServerMessage message, final Queue originatingQueue, final Transaction tx) throws Exception
   {
      Bindings bindings = addressManager.getBindingsForRoutingAddress(message.getDestination());

      if (bindings != null)
      {
         return bindings.redistribute(message, originatingQueue, tx);
      }
      else
      {
         return false;
      }
   }

   public PagingManager getPagingManager()
   {
      return pagingManager;
   }

   public List<Queue> activate()
   {

      backup = false;

      pagingManager.activate();

      Map<SimpleString, Binding> nameMap = addressManager.getBindings();

      List<Queue> queues = new ArrayList<Queue>();

      for (Binding binding : nameMap.values())
      {
         if (binding.getType() == BindingType.LOCAL_QUEUE)
         {
            Queue queue = (Queue)binding.getBindable();

            boolean activated = queue.activate();

            if (!activated)
            {
               queues.add(queue);
            }
         }
      }

      startExpiryScanner();

      return queues;
   }

   public DuplicateIDCache getDuplicateIDCache(final SimpleString address)
   {
      DuplicateIDCache cache = duplicateIDCaches.get(address);

      if (cache == null)
      {
         cache = new DuplicateIDCacheImpl(address, idCacheSize, storageManager, persistIDCache);

         DuplicateIDCache oldCache = duplicateIDCaches.putIfAbsent(address, cache);

         if (oldCache != null)
         {
            cache = oldCache;
         }
      }

      return cache;
   }

   public Object getNotificationLock()
   {
      return notificationLock;
   }

   public void sendQueueInfoToQueue(final SimpleString queueName, final SimpleString address) throws Exception
   {
      // We send direct to the queue so we can send it to the same queue that is bound to the notifications adress -
      // this is crucial for ensuring
      // that queue infos and notifications are received in a contiguous consistent stream
      Binding binding = addressManager.getBinding(queueName);

      if (binding == null)
      {
         throw new IllegalStateException("Cannot find queue " + queueName);
      }

      Queue queue = (Queue)binding.getBindable();

      // Need to lock to make sure all queue info and notifications are in the correct order with no gaps
      synchronized (notificationLock)
      {
         // First send a reset message

         ServerMessage message = new ServerMessageImpl(storageManager.generateUniqueID());
         message.setBody(ChannelBuffers.EMPTY_BUFFER);
         message.setDestination(queueName);
         message.putBooleanProperty(HDR_RESET_QUEUE_DATA, true);
         queue.preroute(message, null);
         queue.route(message, null);

         for (QueueInfo info : queueInfos.values())
         {
            if (info.getAddress().startsWith(address))
            {
               message = createQueueInfoMessage(NotificationType.BINDING_ADDED, queueName);

               message.putStringProperty(ManagementHelper.HDR_ADDRESS, info.getAddress());
               message.putStringProperty(ManagementHelper.HDR_CLUSTER_NAME, info.getClusterName());
               message.putStringProperty(ManagementHelper.HDR_ROUTING_NAME, info.getRoutingName());
               message.putIntProperty(ManagementHelper.HDR_BINDING_ID, info.getID());
               message.putStringProperty(ManagementHelper.HDR_FILTERSTRING, info.getFilterString());
               message.putIntProperty(ManagementHelper.HDR_DISTANCE, info.getDistance());

               routeDirect(queue, message);

               int consumersWithFilters = info.getFilterStrings() != null ? info.getFilterStrings().size() : 0;

               for (int i = 0; i < info.getNumberOfConsumers() - consumersWithFilters; i++)
               {
                  message = createQueueInfoMessage(NotificationType.CONSUMER_CREATED, queueName);

                  message.putStringProperty(ManagementHelper.HDR_ADDRESS, info.getAddress());
                  message.putStringProperty(ManagementHelper.HDR_CLUSTER_NAME, info.getClusterName());
                  message.putStringProperty(ManagementHelper.HDR_ROUTING_NAME, info.getRoutingName());
                  message.putIntProperty(ManagementHelper.HDR_DISTANCE, info.getDistance());

                  routeDirect(queue, message);
               }

               if (info.getFilterStrings() != null)
               {
                  for (SimpleString filterString : info.getFilterStrings())
                  {
                     message = createQueueInfoMessage(NotificationType.CONSUMER_CREATED, queueName);

                     message.putStringProperty(ManagementHelper.HDR_ADDRESS, info.getAddress());
                     message.putStringProperty(ManagementHelper.HDR_CLUSTER_NAME, info.getClusterName());
                     message.putStringProperty(ManagementHelper.HDR_ROUTING_NAME, info.getRoutingName());
                     message.putStringProperty(ManagementHelper.HDR_FILTERSTRING, filterString);
                     message.putIntProperty(ManagementHelper.HDR_DISTANCE, info.getDistance());

                     routeDirect(queue, message);
                  }
               }
            }
         }
      }

   }

   // Private -----------------------------------------------------------------

   private synchronized void startExpiryScanner()
   {

      if (reaperPeriod > 0)
      {
         reaperThread = new Thread(reaperRunnable, "HornetQ-expiry-reaper");

         reaperThread.setPriority(reaperPriority);

         reaperThread.start();
      }
   }

   private void routeDirect(final Queue queue, final ServerMessage message) throws Exception
   {
      if (queue.getFilter() == null || queue.getFilter().match(message))
      {
         queue.preroute(message, null);
         queue.route(message, null);
      }
   }

   private ServerMessage createQueueInfoMessage(final NotificationType type, final SimpleString queueName)
   {
      ServerMessage message = new ServerMessageImpl(storageManager.generateUniqueID());
      message.setBody(ChannelBuffers.EMPTY_BUFFER);

      message.setDestination(queueName);

      String uid = UUIDGenerator.getInstance().generateStringUUID();

      message.putStringProperty(ManagementHelper.HDR_NOTIFICATION_TYPE, new SimpleString(type.toString()));
      message.putLongProperty(ManagementHelper.HDR_NOTIFICATION_TIMESTAMP, System.currentTimeMillis());

      message.putStringProperty(new SimpleString("foobar"), new SimpleString(uid));

      return message;
   }

   private int generateTransientID()
   {
      int start = transientIDSequence;
      do
      {
         int id = transientIDSequence++;

         if (!transientIDs.contains(id))
         {
            transientIDs.add(id);

            return id;
         }
      }
      while (transientIDSequence != start);

      throw new IllegalStateException("Run out of queue ids!");
   }

   private void releaseTransientID(final int id)
   {
      transientIDs.remove(id);
   }

   private final PageMessageOperation getPageOperation(final Transaction tx)
   {
      // you could have races on the case two sessions using the same XID
      // so this whole operation needs to be atomic per TX
      synchronized (tx)
      {
         PageMessageOperation oper = (PageMessageOperation)tx.getProperty(TransactionPropertyIndexes.PAGE_MESSAGES_OPERATION);

         if (oper == null)
         {
            oper = new PageMessageOperation();

            tx.putProperty(TransactionPropertyIndexes.PAGE_MESSAGES_OPERATION, oper);

            tx.addOperation(oper);
         }

         return oper;
      }
   }

   private class Reaper implements Runnable
   {
      private volatile boolean closed = false;

      public synchronized void stop()
      {
         closed = true;

         notify();
      }

      public synchronized void run()
      {
         if (closed)
         {
            // This shouldn't happen in a regular scenario
            log.warn("Reaper thread being restarted");
            closed = false;
         }
         
         // The reaper thread should be finished case the PostOffice is gone
         // This is to avoid leaks on PostOffice between stops and starts
         while (PostOfficeImpl.this.isStarted())
         {
            long toWait = reaperPeriod;

            long start = System.currentTimeMillis();

            while (!closed && toWait > 0)
            {
               try
               {
                  wait(toWait);
               }
               catch (InterruptedException e)
               {
               }

               long now = System.currentTimeMillis();

               toWait -= now - start;

               start = now;
            }

            if (closed)
            {
               return;
            }

            Map<SimpleString, Binding> nameMap = addressManager.getBindings();

            List<Queue> queues = new ArrayList<Queue>();

            for (Binding binding : nameMap.values())
            {
               if (binding.getType() == BindingType.LOCAL_QUEUE)
               {
                  Queue queue = (Queue)binding.getBindable();

                  queues.add(queue);
               }
            }

            for (Queue queue : queues)
            {
               try
               {
                  queue.expireReferences();
               }
               catch (Exception e)
               {
                  log.error("failed to expire messages for queue " + queue.getName(), e);
               }
            }
         }
      }
   }

   private class PageMessageOperation implements TransactionOperation
   {
      private final List<ServerMessage> messagesToPage = new ArrayList<ServerMessage>();

      void addMessageToPage(final ServerMessage message)
      {
         messagesToPage.add(message);
      }

      /* (non-Javadoc)
       * @see org.hornetq.core.transaction.TransactionOperation#getDistinctQueues()
       */
      public Collection<Queue> getDistinctQueues()
      {
         return Collections.emptySet();
      }

      public void afterCommit(final Transaction tx) throws Exception
      {
         // If part of the transaction goes to the queue, and part goes to paging, we can't let depage start for the
         // transaction until all the messages were added to the queue
         // or else we could deliver the messages out of order

         PageTransactionInfo pageTransaction = (PageTransactionInfo)tx.getProperty(TransactionPropertyIndexes.PAGE_TRANSACTION);

         if (pageTransaction != null)
         {
            pageTransaction.commit();
         }
      }

      public void afterPrepare(final Transaction tx) throws Exception
      {
      }

      public void afterRollback(final Transaction tx) throws Exception
      {
         PageTransactionInfo pageTransaction = (PageTransactionInfo)tx.getProperty(TransactionPropertyIndexes.PAGE_TRANSACTION);

         if (tx.getState() == State.PREPARED && pageTransaction != null)
         {
            pageTransaction.rollback();
         }
      }

      public void beforeCommit(final Transaction tx) throws Exception
      {
         if (tx.getState() != Transaction.State.PREPARED)
         {
            pageMessages(tx);
         }
      }

      public void beforePrepare(final Transaction tx) throws Exception
      {
         pageMessages(tx);
      }

      public void beforeRollback(final Transaction tx) throws Exception
      {
      }

      private void pageMessages(final Transaction tx) throws Exception
      {
         if (!messagesToPage.isEmpty())
         {
            PageTransactionInfo pageTransaction = (PageTransactionInfo)tx.getProperty(TransactionPropertyIndexes.PAGE_TRANSACTION);

            if (pageTransaction == null)
            {
               pageTransaction = new PageTransactionInfoImpl(tx.getID());

               tx.putProperty(TransactionPropertyIndexes.PAGE_TRANSACTION, pageTransaction);

               // To avoid a race condition where depage happens before the transaction is completed, we need to inform
               // the pager about this transaction is being processed
               pagingManager.addTransaction(pageTransaction);
            }

            boolean pagingPersistent = false;

            HashSet<SimpleString> pagedDestinationsToSync = new HashSet<SimpleString>();

            // We only need to add the dupl id header once per transaction
            boolean first = true;
            for (ServerMessage message : messagesToPage)
            {
               if (pagingManager.page(message, tx.getID(), first))
               {
                  if (message.isDurable())
                  {
                     // We only create pageTransactions if using persistent messages
                     pageTransaction.increment();
                     pagingPersistent = true;
                     pagedDestinationsToSync.add(message.getDestination());
                  }
               }
               else
               {
                  // This could happen when the PageStore left the pageState

                  // TODO is this correct - don't we lose transactionality here???
                  route(message, null);
               }
               first = false;
            }

            if (pagingPersistent)
            {
               tx.putProperty(TransactionPropertyIndexes.CONTAINS_PERSISTENT, true);

               if (!pagedDestinationsToSync.isEmpty())
               {
                  pagingManager.sync(pagedDestinationsToSync);
                  storageManager.storePageTransaction(tx.getID(), pageTransaction);
               }
            }
         }
      }
   }
}