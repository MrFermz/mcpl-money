package io.papermc.paper.plugin.lifecycle.event.types;

import static org.mockito.Mockito.mock;

import io.papermc.paper.plugin.lifecycle.event.LifecycleEvent;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;

/**
 * Stand-in for the provider a real Paper server supplies, so {@link LifecycleEvents} can be
 * loaded in unit tests. Registered in META-INF/services. Lives in Paper's package because the
 * provider interface is package-private.
 */
public final class TestLifecycleEventTypeProvider implements LifecycleEventTypeProvider {

    @Override
    @SuppressWarnings("unchecked")
    public <O extends LifecycleEventOwner, E extends LifecycleEvent> LifecycleEventType.Monitorable<O, E> monitor(
            String name, Class<? extends O> ownerType) {
        return mock(LifecycleEventType.Monitorable.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <O extends LifecycleEventOwner, E extends LifecycleEvent> LifecycleEventType.Prioritizable<O, E> prioritized(
            String name, Class<? extends O> ownerType) {
        return mock(LifecycleEventType.Prioritizable.class);
    }

    @Override
    public TagEventTypeProvider tagProvider() {
        return mock(TagEventTypeProvider.class);
    }
}
