package org.manager.download.handler;

/**
 * Event-interception contract between the download manager's shared
 * listener and per-download retry wrappers.
 *
 * <p>Handlers are shared per download type and broadcast every event to
 * every registered listener, so a retry wrapper must not register its own
 * broadcast listener: it would process failures belonging to other
 * downloads. Instead the manager's single reusable listener looks up the
 * per-download handler and consults this contract before applying a
 * terminal event.</p>
 */
public interface RetryEventInterceptor {

    /** Manager-side decision for an error event reaching a wrapper. */
    enum RetryDecision {

        /** The wrapper owns this failure and scheduled a retry: the manager
         *  must apply NO terminal handling (no ERROR reindex, no slot
         *  release, no next-queued start). */
        RETRY_SCHEDULED,

        /** The failure is final (exhausted budget or not retryable): the
         *  manager applies its normal terminal handling. */
        PROPAGATE_TERMINAL
    }

    /**
     * Offers an error event to the wrapper. Implementations accept only
     * their own download ID; events for other downloads are ignored.
     *
     * @param downloadId ID of the download the error event belongs to
     * @param errorMessage the reported error message
     * @return the decision for the manager's terminal handling
     */
    RetryDecision interceptError(String downloadId, String errorMessage);

    /**
     * Notifies the wrapper that its download completed so it can finalize
     * (release proxy, cancel any scheduled retry, settle its future).
     * Events for other downloads are ignored.
     *
     * @param downloadId ID of the download that completed
     */
    void interceptComplete(String downloadId);

    /**
     * Notifies the wrapper that its download was canceled so it can
     * finalize. Events for other downloads are ignored.
     *
     * @param downloadId ID of the download that was canceled
     */
    void interceptCanceled(String downloadId);

    /**
     * Notifies the wrapper that its download was started again through a
     * NEW handler, replacing this one: the wrapper must silently retire
     * (invalidate its generation, cancel any scheduled retry) without
     * emitting a terminal outcome. Events for other downloads are ignored.
     *
     * @param downloadId ID of the download that was restarted
     */
    default void interceptReplaced(String downloadId) {
    }
}
