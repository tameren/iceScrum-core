/*
 * Copyright (c) 2014 Kagilum.
 *
 * This file is part of iceScrum.
 *
 * iceScrum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * iceScrum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with iceScrum.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Authors:
 *
 * Vincent Barrier (vbarrier@kagilum.com)
 * Nicolas Noullet (nnoullet@kagilum.com)
 *
 */
package org.icescrum.core.event

import org.icescrum.core.event.IceScrumSynchronousEvent.EventType

abstract class IceScrumEventPushlisher {

    private Map<EventType, List<Closure>> listenersByEventType = [:]

    synchronized registerListener(EventType eventType, Closure listener) {
        def listeners = listenersByEventType[eventType]
        if (listeners == null) {
            def emptyListeners = []
            listenersByEventType[eventType] = emptyListeners
            listeners = emptyListeners
        }
        listener.delegate = this
        listeners.add(listener)
    }

    // This removes the oldest occurrence of the listener (identified by its reference)
    synchronized unregisterListener(EventType eventType, Closure listener) {
        listenersByEventType[eventType]?.remove(listener)
    }

    synchronized executeListeners(IceScrumSynchronousEvent event) {
        listenersByEventType[event.type]?.each { it(event.object, event.dirtyProperties) }
    }
}