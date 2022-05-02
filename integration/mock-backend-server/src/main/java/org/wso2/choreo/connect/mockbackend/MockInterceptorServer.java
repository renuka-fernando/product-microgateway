/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.choreo.connect.mockbackend;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;

public class MockInterceptorServer extends Thread {
    private static final Logger log = LogManager.getLogger(MockInterceptorServer.class.getName());
    private final int statusServerPort;
    private final HandlerServer handlerServer;
    private volatile InterceptorConstants.Handler handler;
    private volatile String requestFlowRequestBody;
    private volatile String requestFlowResponseBody;
    private volatile String responseFlowRequestBody;
    private volatile String responseFlowResponseBody;

    public MockInterceptorServer(int interceptorStatusPort, int handlerPort) {
        statusServerPort = interceptorStatusPort;
        handlerServer = new HandlerServer(handlerPort);
        clearStatus();
    }

    private void clearStatus() {
        synchronized (this) {
            handler = InterceptorConstants.Handler.NONE;
            requestFlowRequestBody = "";
            requestFlowResponseBody = "{}";
            responseFlowRequestBody = "";
            responseFlowResponseBody = "{}";
        }
    }


    @Override
    public void run() {
        if (statusServerPort < 0) {
            throw new RuntimeException("Server port is not defined");
        }

        try {
            HttpServer managerHttpServer = HttpServer.create(new InetSocketAddress(statusServerPort), 0);
            String context = "/interceptor";

            // status
            managerHttpServer.createContext(context + "/status", exchange -> {
                log.info("Reading interceptor service status");
                JSONObject responseJSON = new JSONObject();
                synchronized (this) {
                    responseJSON.put(InterceptorConstants.StatusPayload.HANDLER, handler);
                    responseJSON.put(InterceptorConstants.StatusPayload.REQUEST_FLOW_REQUEST_BODY, requestFlowRequestBody);
                    responseJSON.put(InterceptorConstants.StatusPayload.RESPONSE_FLOW_REQUEST_BODY, responseFlowRequestBody);
                }

                byte[] response = responseJSON.toString().getBytes();
                exchange.getResponseHeaders().set(Constants.CONTENT_TYPE, Constants.CONTENT_TYPE_APPLICATION_JSON);
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });

            // clear status
            managerHttpServer.createContext(context + "/clear-status", exchange -> {
                log.info("Clearing interceptor service status");
                clearStatus();
                Utils.send200OK(exchange);
                exchange.close();
            });

            // set response of request flow interceptor
            managerHttpServer.createContext(context + "/request", exchange -> {
                log.info("Setting request interceptor service body tobe returned");
                synchronized (this) {
                    requestFlowResponseBody = Utils.requestBodyToString(exchange);
                }
                Utils.send200OK(exchange);
                exchange.close();
            });

            // set response of response flow interceptor
            managerHttpServer.createContext(context + "/response", exchange -> {
                log.info("Setting response interceptor service body tobe returned");
                synchronized (this) {
                    responseFlowResponseBody = Utils.requestBodyToString(exchange);
                }
                Utils.send200OK(exchange);
                exchange.close();
            });

            managerHttpServer.start();
        } catch (Exception ex) {
            log.error("Error occurred while setting up interceptor server", ex);
        }
    }

    @Override
    public void start() {
        super.start();
        handlerServer.start();
    }

    private class HandlerServer extends Thread {
        private final Logger log = LogManager.getLogger(HandlerServer.class.getName());
        private final int handlerServerPort;

        public HandlerServer(int port) {
            handlerServerPort = port;
        }

        @Override
        public void run() {
            if (handlerServerPort < 0) {
                throw new RuntimeException("Server port is not defined");
            }
            try {
                HttpsServer httpServer = HttpsServer.create(new InetSocketAddress(handlerServerPort), 0);
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(
                        Utils.getKeyManagers("interceptorKeystore.pkcs12", "interceptor"), // Created using interceptorKeystore.pem
                        Utils.getTrustManagers(), null);

                httpServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                    public void configure(HttpsParameters params) {
                        try {
                            SSLContext sslContext = SSLContext.getDefault();
                            SSLEngine engine = sslContext.createSSLEngine();

                            SSLParameters sslParameters = sslContext
                                    .getDefaultSSLParameters();
                            sslParameters.setCipherSuites(engine.getEnabledCipherSuites());
                            sslParameters.setNeedClientAuth(true);
                            sslParameters.setProtocols(engine.getEnabledProtocols());
                            params.setSSLParameters(sslParameters);
                        } catch (Exception ex) {
                            log.error("Failed to create HTTPS port");
                        }
                    }
                });

                String context = "/api/v1";
                // handle request
                httpServer.createContext(context + "/handle-request", exchange -> {
                    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        Utils.send404NotFound(exchange);
                        return;
                    }

                    log.info("Called /handle-request of interceptor service");
                    byte[] response;
                    synchronized (this) {
                        requestFlowRequestBody = Utils.requestBodyToString(exchange);
                        response = requestFlowResponseBody.getBytes();
                        // set which flow has handled by interceptor
                        if (handler == InterceptorConstants.Handler.NONE || handler == InterceptorConstants.Handler.REQUEST_ONLY) {
                            handler = InterceptorConstants.Handler.REQUEST_ONLY;
                        } else {
                            handler = InterceptorConstants.Handler.BOTH;
                        }
                    }
                    exchange.getResponseHeaders().set(Constants.CONTENT_TYPE, Constants.CONTENT_TYPE_APPLICATION_JSON);
                    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });

                // handle response
                httpServer.createContext(context + "/handle-response", exchange -> {
                    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        Utils.send404NotFound(exchange);
                        return;
                    }

                    log.info("Called /handle-response of interceptor service");
                    byte[] response;
                    synchronized (this){
                        responseFlowRequestBody = Utils.requestBodyToString(exchange);
                        response = responseFlowResponseBody.getBytes();
                        // set which flow has handled by interceptor
                        if (handler == InterceptorConstants.Handler.NONE || handler == InterceptorConstants.Handler.RESPONSE_ONLY) {
                            handler = InterceptorConstants.Handler.RESPONSE_ONLY;
                        } else {
                            handler = InterceptorConstants.Handler.BOTH;
                        }
                    }
                    exchange.getResponseHeaders().set(Constants.CONTENT_TYPE, Constants.CONTENT_TYPE_APPLICATION_JSON);
                    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });

                httpServer.start();
            } catch (Exception ex) {
                log.error("Error occurred while setting up interceptor handler server", ex);
            }
        }
    }
}
