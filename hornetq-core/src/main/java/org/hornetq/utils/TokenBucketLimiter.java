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

package org.hornetq.utils;

/**
 * This class can throttle to a specific rate, using an algorithm based on the <a
 * href="http://en.wikipedia.org/wiki/Token_bucket">Token Bucket metaphor</a>.
 * <p>
 * The rate is specified in cycles per second (or 'Hertz').
 * @see http://en.wikipedia.org/wiki/Token_bucket
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 */
public interface TokenBucketLimiter
{
   /**
    * Returns the rate in cycles per second (which is the same as saying 'in Hertz').
    * @see https://en.wikipedia.org/wiki/Hertz
    */
   int getRate();

   boolean isSpin();

   void limit();
}
