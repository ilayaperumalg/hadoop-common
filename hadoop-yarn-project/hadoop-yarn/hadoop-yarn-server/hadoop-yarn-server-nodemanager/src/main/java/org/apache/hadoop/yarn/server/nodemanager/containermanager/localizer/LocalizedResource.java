/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer;

import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.ContainerResourceLocalizedEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.LocalizerResourceRequestEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.ResourceEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.ResourceEventType;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.ResourceLocalizedEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.ResourceReleaseEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.ResourceRequestEvent;
import org.apache.hadoop.yarn.state.InvalidStateTransitonException;
import org.apache.hadoop.yarn.state.MultipleArcTransition;
import org.apache.hadoop.yarn.state.SingleArcTransition;
import org.apache.hadoop.yarn.state.StateMachine;
import org.apache.hadoop.yarn.state.StateMachineFactory;

/**
 * Datum representing a localized resource. Holds the statemachine of a
 * resource. State of the resource is one of {@link ResourceState}.
 * 
 */
public class LocalizedResource implements EventHandler<ResourceEvent> {

  private static final Log LOG = LogFactory.getLog(LocalizedResource.class);

  Path localPath;
  long size = -1;
  final LocalResourceRequest rsrc;
  final Dispatcher dispatcher;
  final StateMachine<ResourceState,ResourceEventType,ResourceEvent>
    stateMachine;
  final Semaphore sem = new Semaphore(1);
  final Queue<ContainerId> ref; // Queue of containers using this localized
                                // resource
  private final Lock readLock;
  private final Lock writeLock;

  final AtomicLong timestamp = new AtomicLong(currentTime());

  private static final StateMachineFactory<LocalizedResource,ResourceState,
      ResourceEventType,ResourceEvent> stateMachineFactory =
        new StateMachineFactory<LocalizedResource,ResourceState,
          ResourceEventType,ResourceEvent>(ResourceState.INIT)

    // From INIT (ref == 0, awaiting req)
    .addTransition(ResourceState.INIT, ResourceState.DOWNLOADING,
        ResourceEventType.REQUEST, new FetchResourceTransition())
    .addTransition(ResourceState.INIT, ResourceState.LOCALIZED,
        ResourceEventType.LOCALIZED, new FetchDirectTransition())
    .addTransition(ResourceState.INIT, ResourceState.INIT,
        ResourceEventType.RELEASE, new ReleaseTransition())

    // From DOWNLOADING (ref > 0, may be localizing)
    .addTransition(ResourceState.DOWNLOADING, ResourceState.DOWNLOADING,
        ResourceEventType.REQUEST, new FetchResourceTransition()) // TODO: Duplicate addition!!
    .addTransition(ResourceState.DOWNLOADING, ResourceState.LOCALIZED,
        ResourceEventType.LOCALIZED, new FetchSuccessTransition())
    .addTransition(ResourceState.DOWNLOADING,
        EnumSet.of(ResourceState.DOWNLOADING, ResourceState.INIT),
        ResourceEventType.RELEASE, new ReleasePendingTransition())

    // From LOCALIZED (ref >= 0, on disk)
    .addTransition(ResourceState.LOCALIZED, ResourceState.LOCALIZED,
        ResourceEventType.REQUEST, new LocalizedResourceTransition())
    .addTransition(ResourceState.LOCALIZED, ResourceState.LOCALIZED,
        ResourceEventType.LOCALIZED)
    .addTransition(ResourceState.LOCALIZED, ResourceState.LOCALIZED,
        ResourceEventType.RELEASE, new ReleaseTransition())
    .installTopology();

  public LocalizedResource(LocalResourceRequest rsrc, Dispatcher dispatcher) {
    this.rsrc = rsrc;
    this.dispatcher = dispatcher;
    this.ref = new LinkedList<ContainerId>();

    ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    this.readLock = readWriteLock.readLock();
    this.writeLock = readWriteLock.writeLock();

    this.stateMachine = stateMachineFactory.make(this);
  }

  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("{ ").append(rsrc.toString()).append(",")
      .append(getState() == ResourceState.LOCALIZED
          ? getLocalPath() + "," + getSize()
          : "pending").append(",[");
    for (ContainerId c : ref) {
      sb.append("(").append(c.toString()).append(")");
    }
    sb.append("],").append(getTimestamp()).append(",")
      .append(getState()).append("}");
    return sb.toString();
  }

  private void release(ContainerId container) {
    if (!ref.remove(container)) {
      LOG.info("Attempt to release claim on " + this +
               " from unregistered container " + container);
      assert false; // TODO: FIX
    }
    timestamp.set(currentTime());
  }

  private long currentTime() {
    return System.nanoTime();
  }

  public ResourceState getState() {
    this.readLock.lock();
    try {
      return stateMachine.getCurrentState();
    } finally {
      this.readLock.unlock();
    }
  }

  public LocalResourceRequest getRequest() {
    return rsrc;
  }

  public Path getLocalPath() {
    return localPath;
  }

  public long getTimestamp() {
    return timestamp.get();
  }

  public long getSize() {
    return size;
  }

  public int getRefCount() {
    return ref.size();
  }

  public boolean tryAcquire() {
    return sem.tryAcquire();
  }

  public void unlock() {
    sem.release();
  }

  @Override
  public void handle(ResourceEvent event) {
    try {
      this.writeLock.lock();

      Path resourcePath = event.getLocalResourceRequest().getPath();
      LOG.debug("Processing " + resourcePath + " of type " + event.getType());

      ResourceState oldState = this.stateMachine.getCurrentState();
      ResourceState newState = null;
      try {
        newState = this.stateMachine.doTransition(event.getType(), event);
      } catch (InvalidStateTransitonException e) {
        LOG.warn("Can't handle this event at current state", e);
      }
      if (oldState != newState) {
        LOG.info("Resource " + resourcePath + " transitioned from "
            + oldState
            + " to " + newState);
      }
    } finally {
      this.writeLock.unlock();
    }
  }

  static abstract class ResourceTransition implements
      SingleArcTransition<LocalizedResource,ResourceEvent> {
    // typedef
  }

  /**
   * Transition from INIT to DOWNLOADING.
   * Sends a {@link LocalizerResourceRequestEvent} to the
   * {@link ResourceLocalizationService}.
   */
  @SuppressWarnings("unchecked") // dispatcher not typed
  private static class FetchResourceTransition extends ResourceTransition {
    @Override
    public void transition(LocalizedResource rsrc, ResourceEvent event) {
      ResourceRequestEvent req = (ResourceRequestEvent) event;
      LocalizerContext ctxt = req.getContext();
      ContainerId container = ctxt.getContainerId();
      rsrc.ref.add(container);
      rsrc.dispatcher.getEventHandler().handle(
          new LocalizerResourceRequestEvent(rsrc, req.getVisibility(), ctxt));
    }
  }

  private static class FetchDirectTransition extends FetchSuccessTransition {
    @Override
    public void transition(LocalizedResource rsrc, ResourceEvent event) {
      LOG.warn("Resource " + rsrc + " localized without listening container");
      super.transition(rsrc, event);
    }
  }

  /**
   * Resource localized, notify waiting containers.
   */
  @SuppressWarnings("unchecked") // dispatcher not typed
  private static class FetchSuccessTransition extends ResourceTransition {
    @Override
    public void transition(LocalizedResource rsrc, ResourceEvent event) {
      ResourceLocalizedEvent locEvent = (ResourceLocalizedEvent) event;
      rsrc.localPath = locEvent.getLocation();
      rsrc.size = locEvent.getSize();
      for (ContainerId container : rsrc.ref) {
        rsrc.dispatcher.getEventHandler().handle(
            new ContainerResourceLocalizedEvent(
              container, rsrc.rsrc, rsrc.localPath));
      }
    }
  }

  /**
   * Resource already localized, notify immediately.
   */
  @SuppressWarnings("unchecked") // dispatcher not typed
  private static class LocalizedResourceTransition
      extends ResourceTransition {
    @Override
    public void transition(LocalizedResource rsrc, ResourceEvent event) {
      // notify waiting containers
      ResourceRequestEvent reqEvent = (ResourceRequestEvent) event;
      ContainerId container = reqEvent.getContext().getContainerId();
      rsrc.ref.add(container);
      rsrc.dispatcher.getEventHandler().handle(
          new ContainerResourceLocalizedEvent(
            container, rsrc.rsrc, rsrc.localPath));
    }
  }

  /**
   * Decrement resource count, update timestamp.
   */
  private static class ReleaseTransition extends ResourceTransition {
    @Override
    public void transition(LocalizedResource rsrc, ResourceEvent event) {
      // Note: assumes that localizing container must succeed or fail
      ResourceReleaseEvent relEvent = (ResourceReleaseEvent) event;
      rsrc.release(relEvent.getContainer());
    }
  }

  private static class ReleasePendingTransition implements
      MultipleArcTransition<LocalizedResource,ResourceEvent,ResourceState> {
    @Override
    public ResourceState transition(LocalizedResource rsrc,
        ResourceEvent event) {
      ResourceReleaseEvent relEvent = (ResourceReleaseEvent) event;
      rsrc.release(relEvent.getContainer());
      return rsrc.ref.isEmpty()
        ? ResourceState.INIT
        : ResourceState.DOWNLOADING;
    }
  }
}
