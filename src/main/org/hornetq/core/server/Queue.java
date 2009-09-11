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

package org.hornetq.core.server;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import org.hornetq.core.filter.Filter;
import org.hornetq.core.remoting.Channel;
import org.hornetq.core.transaction.Transaction;
import org.hornetq.utils.SimpleString;

/**
 * 
 * A Queue
 * 
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="ataylor@redhat.com">Andy Taylor</a>
 * @author <a href="clebert.suconic@jboss.com">Clebert Suconic</a>
 *
 */
public interface Queue extends Bindable
{
   MessageReference reroute(ServerMessage message, Transaction tx) throws Exception;

   SimpleString getName();

   long getPersistenceID();

   void setPersistenceID(long id);

   Filter getFilter();

   boolean isDurable();

   boolean isTemporary();

   void addConsumer(Consumer consumer) throws Exception;

   boolean removeConsumer(Consumer consumer) throws Exception;

   int getConsumerCount();

   Set<Consumer> getConsumers();

   void addLast(MessageReference ref);

   void addFirst(MessageReference ref);

   void acknowledge(MessageReference ref) throws Exception;

   void acknowledge(Transaction tx, MessageReference ref) throws Exception;

   void reacknowledge(Transaction tx, MessageReference ref) throws Exception;

   void cancel(Transaction tx, MessageReference ref) throws Exception;

   void cancel(MessageReference reference) throws Exception;

   void deliverAsync(Executor executor);

   List<MessageReference> list(Filter filter);

   int getMessageCount();

   int getDeliveringCount();

   void referenceHandled();

   int getScheduledCount();

   List<MessageReference> getScheduledMessages();

   Distributor getDistributionPolicy();

   void setDistributionPolicy(Distributor policy);

   int getMessagesAdded();

   MessageReference removeReferenceWithID(long id) throws Exception;
   
   MessageReference removeFirstReference(long id) throws Exception;

   MessageReference getReference(long id);

   int deleteAllReferences() throws Exception;

   boolean deleteReference(long messageID) throws Exception;

   int deleteMatchingReferences(Filter filter) throws Exception;

   boolean expireReference(long messageID) throws Exception;

   /**
    * Expire all the references in the queue which matches the filter
    */
   int expireReferences(Filter filter) throws Exception;

   void expireReferences() throws Exception;

   void expire(MessageReference ref) throws Exception;

   boolean sendMessageToDeadLetterAddress(long messageID) throws Exception;

   boolean changeReferencePriority(long messageID, byte newPriority) throws Exception;

   boolean moveReference(long messageID, SimpleString toAddress) throws Exception;

   int moveReferences(Filter filter, SimpleString toAddress) throws Exception;

   void setBackup();

   boolean activate();

   void activateNow(Executor executor);

   boolean isBackup();

   boolean consumerFailedOver();

   void addRedistributor(long delay, Executor executor, final Channel replicatingChannel);

   void cancelRedistributor() throws Exception;

   // Only used in testing
   void deliverNow();

   boolean checkDLQ(MessageReference ref) throws Exception;
   
   void lockDelivery();
   
   void unlockDelivery();

   /**
    * @return an immutable iterator which does not allow to remove references
    */
   Iterator<MessageReference> iterator();
   
   void setExpiryAddress(SimpleString expiryAddress);
}