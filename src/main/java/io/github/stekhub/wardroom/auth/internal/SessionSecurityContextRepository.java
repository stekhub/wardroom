package io.github.stekhub.wardroom.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Reads the login from the session, with two rules of spec 001 on top of Spring Security's own behavior:
 *
 * <ul>
 * <li>A stored session that cannot be read, for example after an update, is deleted and counts as logged out.</li>
 * <li>A session whose account no longer exists counts as logged out.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SessionSecurityContextRepository implements SecurityContextRepository {

    private final HttpSessionSecurityContextRepository httpSessionContextRepository =
            new HttpSessionSecurityContextRepository();
    private final AccountRepository accountRepository;

    @Override
    @SuppressWarnings("deprecation") // Still part of the interface; Spring Security itself uses loadDeferredContext.
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        return loadDeferredContext(requestResponseHolder.getRequest()).get();
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return new LoadedOnFirstUse(() -> readContext(request));
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        httpSessionContextRepository.saveContext(context, request, response);
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return readContext(request) != null;
    }

    /** @return the login stored in the session, or {@code null} if the request is not logged in */
    private SecurityContext readContext(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        SecurityContext context;
        try {
            context = httpSessionContextRepository.loadDeferredContext(request).get();
        } catch (DataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            // The exception is not logged: it can contain the stored bytes of the session.
            log.warn("A stored session could not be read and was deleted; its browser has to log in again");
            session.invalidate();
            return null;
        }
        Authentication authentication = context.getAuthentication();
        if (authentication == null) {
            return null;
        }
        if (!accountRepository.accountExists(authentication.getName())) {
            session.invalidate();
            return null;
        }
        return context;
    }

    /** Reads the session only when the login is needed, and only once per request. */
    private static final class LoadedOnFirstUse implements DeferredSecurityContext {

        private final Supplier<SecurityContext> reader;
        private SecurityContext context;
        private boolean generated;

        private LoadedOnFirstUse(Supplier<SecurityContext> reader) {
            this.reader = reader;
        }

        @Override
        public SecurityContext get() {
            if (context == null) {
                context = reader.get();
                if (context == null) {
                    context = SecurityContextHolder.createEmptyContext();
                    generated = true;
                }
            }
            return context;
        }

        @Override
        public boolean isGenerated() {
            get();
            return generated;
        }
    }
}
