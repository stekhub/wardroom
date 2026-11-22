package io.github.stekhub.wardroom.articles.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Threads at the start what has no place in a thread: articles that were stored before threads existed, and
 * articles whose threading failed. Without such articles it does nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ThreadStartup {

    private final ThreadService threadService;

    @EventListener(ApplicationReadyEvent.class)
    void threadArticlesWithoutPlace() {
        try {
            threadService.threadArticlesWithoutPlace();
        } catch (RuntimeException e) {
            log.error("The articles without a place in a thread could not be threaded at the start. The next"
                    + " synchronization of their group tries again.", e);
        }
    }
}
