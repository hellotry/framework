/*
 * Copyright 2000-2013 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.server.communication;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResource.TRANSPORT;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.json.JSONException;

import com.vaadin.server.ErrorEvent;
import com.vaadin.server.ErrorHandler;
import com.vaadin.server.LegacyCommunicationManager.InvalidUIDLSecurityKeyException;
import com.vaadin.server.ServiceException;
import com.vaadin.server.ServletPortletHelper;
import com.vaadin.server.SessionExpiredException;
import com.vaadin.server.SystemMessages;
import com.vaadin.server.VaadinRequest;
import com.vaadin.server.VaadinService;
import com.vaadin.server.VaadinServletRequest;
import com.vaadin.server.VaadinServletService;
import com.vaadin.server.VaadinSession;
import com.vaadin.shared.ApplicationConstants;
import com.vaadin.shared.communication.PushMode;
import com.vaadin.ui.UI;

/**
 * Establishes bidirectional ("push") communication channels
 * 
 * @author Vaadin Ltd
 * @since 7.1
 */
public class PushHandler extends AtmosphereResourceEventListenerAdapter
        implements AtmosphereHandler {

    /**
     * Callback interface used internally to process an event with the
     * corresponding UI properly locked.
     */
    private interface PushEventCallback {
        public void run(AtmosphereResource resource, UI ui) throws IOException;
    }

    /**
     * Callback used when we receive a request to establish a push channel for a
     * UI. Associate the AtmosphereResource with the UI and leave the connection
     * open by calling resource.suspend(). If there is a pending push, send it
     * now.
     */
    private final PushEventCallback establishCallback = new PushEventCallback() {
        @Override
        public void run(AtmosphereResource resource, UI ui) throws IOException {
            getLogger().log(Level.FINER,
                    "New push connection for resource {0} with transport {1}",
                    new Object[] { resource.uuid(), resource.transport() });

            resource.addEventListener(PushHandler.this);

            resource.getResponse().setContentType("text/plain; charset=UTF-8");

            VaadinSession session = ui.getSession();
            if (resource.transport() == TRANSPORT.STREAMING) {
                // Must ensure that the streaming response contains
                // "Connection: close", otherwise iOS 6 will wait for the
                // response to this request before sending another request to
                // the same server (as it will apparently try to reuse the same
                // connection)
                resource.getResponse().addHeader("Connection", "close");
            }

            String requestToken = resource.getRequest().getParameter(
                    ApplicationConstants.CSRF_TOKEN_PARAMETER);
            if (!VaadinService.isCsrfTokenValid(session, requestToken)) {
                getLogger()
                        .log(Level.WARNING,
                                "Invalid CSRF token in new connection received from {0}",
                                resource.getRequest().getRemoteHost());
                // Refresh on client side, create connection just for
                // sending a message
                sendRefreshAndDisconnect(resource);
                return;
            }

            resource.suspend();

            AtmospherePushConnection connection = getConnectionForUI(ui);
            assert (connection != null);
            connection.connect(resource);
        }
    };

    /**
     * Callback used when we receive a UIDL request through Atmosphere. If the
     * push channel is bidirectional (websockets), the request was sent via the
     * same channel. Otherwise, the client used a separate AJAX request. Handle
     * the request and send changed UI state via the push channel (we do not
     * respond to the request directly.)
     */
    private final PushEventCallback receiveCallback = new PushEventCallback() {
        @Override
        public void run(AtmosphereResource resource, UI ui) throws IOException {
            getLogger().log(Level.FINER, "Received message from resource {0}",
                    resource.uuid());

            AtmosphereRequest req = resource.getRequest();

            AtmospherePushConnection connection = getConnectionForUI(ui);

            assert connection != null : "Got push from the client "
                    + "even though the connection does not seem to be "
                    + "valid. This might happen if a HttpSession is "
                    + "serialized and deserialized while the push "
                    + "connection is kept open or if the UI has a "
                    + "connection of unexpected type.";

            Reader reader = connection.receiveMessage(req.getReader());
            if (reader == null) {
                // The whole message was not yet received
                return;
            }

            // Should be set up by caller
            VaadinRequest vaadinRequest = VaadinService.getCurrentRequest();
            assert vaadinRequest != null;

            try {
                new ServerRpcHandler().handleRpc(ui, reader, vaadinRequest);
                connection.push(false);
            } catch (JSONException e) {
                getLogger().log(Level.SEVERE, "Error writing JSON to response",
                        e);
                // Refresh on client side
                sendRefreshAndDisconnect(resource);
            } catch (InvalidUIDLSecurityKeyException e) {
                getLogger().log(Level.WARNING,
                        "Invalid security key received from {0}",
                        resource.getRequest().getRemoteHost());
                // Refresh on client side
                sendRefreshAndDisconnect(resource);
            }
        }
    };

    /**
     * Callback used when a connection is closed, either deliberately or because
     * an error occurred.
     */
    private final PushEventCallback disconnectCallback = new PushEventCallback() {
        @Override
        public void run(AtmosphereResource resource, UI ui) throws IOException {
            PushMode pushMode = ui.getPushConfiguration().getPushMode();
            AtmospherePushConnection connection = getConnectionForUI(ui);

            String id = resource.uuid();

            if (connection == null) {
                getLogger()
                        .log(Level.WARNING,
                                "Could not find push connection to close: {0} with transport {1}",
                                new Object[] { id, resource.transport() });
            } else {
                if (!pushMode.isEnabled()) {
                    /*
                     * The client is expected to close the connection after push
                     * mode has been set to disabled.
                     */
                    getLogger().log(Level.FINER,
                            "Connection closed for resource {0}", id);
                } else {
                    /*
                     * Unexpected cancel, e.g. if the user closes the browser
                     * tab.
                     */
                    getLogger()
                            .log(Level.FINER,
                                    "Connection unexpectedly closed for resource {0} with transport {1}",
                                    new Object[] { id, resource.transport() });
                }
                connection.disconnect();
            }
        }
    };

    private VaadinServletService service;

    public PushHandler(VaadinServletService service) {
        this.service = service;
    }

    /**
     * Find the UI for the atmosphere resource, lock it and invoke the callback.
     * 
     * @param resource
     *            the atmosphere resource for the current request
     * @param callback
     *            the push callback to call when a UI is found and locked
     */
    private void callWithUi(final AtmosphereResource resource,
            final PushEventCallback callback) {
        AtmosphereRequest req = resource.getRequest();
        VaadinServletRequest vaadinRequest = new VaadinServletRequest(req,
                service);
        VaadinSession session = null;

        service.requestStart(vaadinRequest, null);
        try {
            try {
                session = service.findVaadinSession(vaadinRequest);
            } catch (ServiceException e) {
                getLogger().log(Level.SEVERE,
                        "Could not get session. This should never happen", e);
                return;
            } catch (SessionExpiredException e) {
                SystemMessages msg = service.getSystemMessages(
                        ServletPortletHelper.findLocale(null, null,
                                vaadinRequest), vaadinRequest);
                sendNotificationAndDisconnect(
                        resource,
                        VaadinService.createCriticalNotificationJSON(
                                msg.getSessionExpiredCaption(),
                                msg.getSessionExpiredMessage(), null,
                                msg.getSessionExpiredURL()));
                return;
            }

            UI ui = null;
            session.lock();
            try {
                VaadinSession.setCurrent(session);
                // Sets UI.currentInstance
                ui = service.findUI(vaadinRequest);
                if (ui == null) {
                    sendNotificationAndDisconnect(resource,
                            UidlRequestHandler.getUINotFoundErrorJSON(service,
                                    vaadinRequest));
                } else {
                    callback.run(resource, ui);
                }
            } catch (final IOException e) {
                callErrorHandler(session, e);
            } catch (final Exception e) {
                SystemMessages msg = service.getSystemMessages(
                        ServletPortletHelper.findLocale(null, null,
                                vaadinRequest), vaadinRequest);

                AtmosphereResource errorResource = resource;
                if (ui != null && ui.getPushConnection() != null) {
                    // We MUST use the opened push connection if there is one.
                    // Otherwise we will write the response to the wrong request
                    // when using streaming (the client -> server request
                    // instead of the opened push channel)
                    errorResource = ((AtmospherePushConnection) ui
                            .getPushConnection()).getResource();
                }

                sendNotificationAndDisconnect(
                        errorResource,
                        VaadinService.createCriticalNotificationJSON(
                                msg.getInternalErrorCaption(),
                                msg.getInternalErrorMessage(), null,
                                msg.getInternalErrorURL()));
                callErrorHandler(session, e);
            } finally {
                try {
                    session.unlock();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING,
                            "Error while unlocking session", e);
                    // can't call ErrorHandler, we (hopefully) don't have a lock
                }
            }
        } finally {
            try {
                service.requestEnd(vaadinRequest, null, session);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error while ending request", e);

                // can't call ErrorHandler, we don't have a lock
            }
        }
    }

    /**
     * Call the session's {@link ErrorHandler}, if it has one, with the given
     * exception wrapped in an {@link ErrorEvent}.
     */
    private void callErrorHandler(VaadinSession session, Exception e) {
        try {
            ErrorHandler errorHandler = ErrorEvent.findErrorHandler(session);
            if (errorHandler != null) {
                errorHandler.error(new ErrorEvent(e));
            }
        } catch (Exception ex) {
            // Let's not allow error handling to cause trouble; log fails
            getLogger().log(Level.WARNING, "ErrorHandler call failed", ex);
        }
    }

    @Override
    public void onRequest(AtmosphereResource resource) {
        AtmosphereRequest req = resource.getRequest();

        if (req.getMethod().equalsIgnoreCase("GET")) {
            callWithUi(resource, establishCallback);
        } else if (req.getMethod().equalsIgnoreCase("POST")) {
            callWithUi(resource, receiveCallback);
        }
    }

    private static AtmospherePushConnection getConnectionForUI(UI ui) {
        PushConnection pushConnection = ui.getPushConnection();
        if (pushConnection instanceof AtmospherePushConnection) {
            return (AtmospherePushConnection) pushConnection;
        } else {
            return null;
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        AtmosphereResource resource = event.getResource();

        String id = resource.uuid();
        if (event.isCancelled() || event.isResumedOnTimeout()) {
            getLogger().log(Level.FINER,
                    "Cancelled connection for resource {0}", id);
            disconnect(event);
        } else if (event.isResuming()) {
            // A connection that was suspended earlier was resumed (committed to
            // the client.) Should only happen if the transport is JSONP or
            // long-polling.
            getLogger().log(Level.FINER, "Resuming request for resource {0}",
                    id);
        } else {
            // A message was broadcast to this resource and should be sent to
            // the client. We don't do any actual broadcasting, in the sense of
            // sending to multiple recipients; any UIDL message is specific to a
            // single client.
            getLogger().log(Level.FINER, "Writing message to resource {0}", id);

            Writer writer = resource.getResponse().getWriter();
            writer.write(event.getMessage().toString());

            switch (resource.transport()) {
            case WEBSOCKET:
                break;
            case SSE:
            case STREAMING:
                writer.flush();
                break;
            case JSONP:
            case LONG_POLLING:
                disconnect(event);
                break;
            default:
                getLogger().log(Level.SEVERE, "Unknown transport {0}",
                        resource.transport());
            }
        }
    }

    @Override
    public void onDisconnect(AtmosphereResourceEvent event) {
        // Log event on trace level
        super.onDisconnect(event);
        disconnect(event);
    }

    @Override
    public void onThrowable(AtmosphereResourceEvent event) {
        getLogger().log(Level.SEVERE, "Exception in push connection",
                event.throwable());
        disconnect(event);
    }

    @Override
    public void destroy() {
    }

    private void disconnect(AtmosphereResourceEvent event) {
        callWithUi(event.getResource(), disconnectCallback);
    }

    /**
     * Sends a refresh message to the given atmosphere resource. Uses an
     * AtmosphereResource instead of an AtmospherePushConnection even though it
     * might be possible to look up the AtmospherePushConnection from the UI to
     * ensure border cases work correctly, especially when there temporarily are
     * two push connections which try to use the same UI. Using the
     * AtmosphereResource directly guarantees the message goes to the correct
     * recipient.
     * 
     * @param resource
     *            The atmosphere resource to send refresh to
     * 
     */
    private static void sendRefreshAndDisconnect(AtmosphereResource resource)
            throws IOException {
        AtmospherePushConnection connection = new AtmospherePushConnection(null);
        connection.connect(resource);
        try {
            connection.sendMessage(VaadinService
                    .createCriticalNotificationJSON(null, null, null, null));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Tries to send a critical notification to the client and close the
     * connection. Does nothing if the connection is already closed.
     */
    private static void sendNotificationAndDisconnect(
            AtmosphereResource resource, String notificationJson) {
        // TODO Implemented differently from sendRefreshAndDisconnect
        try {
            resource.getResponse().getWriter().write(notificationJson);
            resource.resume();
        } catch (Exception e) {
            getLogger().log(Level.FINEST,
                    "Failed to send critical notification to client", e);
        }
    }

    private static final Logger getLogger() {
        return Logger.getLogger(PushHandler.class.getName());
    }
}
