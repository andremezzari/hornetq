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

package org.hornetq.core.journal;


/**
 * 
 * A TestableJournal
 * 
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="mailto:clebert.suconic@jboss.com">Clebert Suconic</a>
 *
 */
public interface TestableJournal extends Journal
{
   int getDataFilesCount();

   int getFreeFilesCount();

   int getOpenedFilesCount();

   int getIDMapSize();

   String debug() throws Exception;

   void debugWait() throws Exception;

   int getFileSize();

   int getMinFiles();

   String getFilePrefix();

   String getFileExtension();

   int getMaxAIO();

   /** This method could be promoted to {@link Journal} interface when we decide to use the loadManager 
    *  instead of load(List,List)
    */
   long load(LoaderCallback reloadManager) throws Exception;

   void forceMoveNextFile() throws Exception;

   void setAutoReclaim(boolean autoReclaim);

   boolean isAutoReclaim();

   

}