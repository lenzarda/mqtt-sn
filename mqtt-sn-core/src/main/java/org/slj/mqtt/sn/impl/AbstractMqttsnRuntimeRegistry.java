/*
 * Copyright (c) 2021 Simon Johnson <simon622 AT gmail DOT com>
 *
 * Find me on GitHub:
 * https://github.com/simon622
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.slj.mqtt.sn.impl;

import org.slj.mqtt.sn.model.MqttsnContext;
import org.slj.mqtt.sn.model.MqttsnOptions;
import org.slj.mqtt.sn.net.MqttsnUdpTransport;
import org.slj.mqtt.sn.net.NetworkAddress;
import org.slj.mqtt.sn.net.NetworkContext;
import org.slj.mqtt.sn.spi.*;

import java.util.*;

/**
 * The base runtime registry provides support for simple fluent construction and encapsulates
 * both the controllers and the configuration for a runtime. Each controller has access to the
 * registry and uses it to access other parts of the application and config.
 *
 * During startup, the registry will be validated to ensure all required components are available.
 * Extending implementations should provide convenience methods for out-of-box runtimes.
 */
public abstract class AbstractMqttsnRuntimeRegistry implements IMqttsnRuntimeRegistry {

    protected MqttsnOptions options;
    protected AbstractMqttsnRuntime runtime;
    protected INetworkAddressRegistry networkAddressRegistry;
    protected IMqttsnStorageService storageService;
    protected IMqttsnCodec codec;
    protected IMqttsnMessageFactory factory;
    protected List<IMqttsnService> services;

    public AbstractMqttsnRuntimeRegistry(IMqttsnStorageService storageService, MqttsnOptions options){
        this.options = options;
        this.storageService = storageService;
        services = new ArrayList<>();
    }

    @Override
    public void init() {
        validateOnStartup();
        initNetworkRegistry();
        factory = codec.createMessageFactory();

        //ensure the storage system is added to managed lifecycle
        withService(storageService);
    }

    protected void initNetworkRegistry(){
        //-- ensure initial definitions exist in the network registry
        if(options.getNetworkAddressEntries() != null && !options.getNetworkAddressEntries().isEmpty()){
            Iterator<String> itr = options.getNetworkAddressEntries().keySet().iterator();
            while(itr.hasNext()){
                String key = itr.next();
                NetworkAddress address = options.getNetworkAddressEntries().get(key);
                NetworkContext networkContext = new NetworkContext(address);
                MqttsnContext sessionContext = new MqttsnContext(key);
                sessionContext.setProtocolVersion(getCodec().getProtocolVersion());
                networkAddressRegistry.bindContexts(networkContext, sessionContext);
            }
        }
    }

    /**
     * NOTE: this is optional
     * When contributed, this controller is used by the queue processor to check if the application is in a fit state to
     * offload messages to a remote (gateway or client), and is called back by the queue processor to be notified of
     * the queue being empty after having been flushed.
     *
     * @param queueProcessorStateCheckService - The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withQueueProcessorStateCheck(IMqttsnQueueProcessorStateService queueProcessorStateCheckService){
        withService(queueProcessorStateCheckService);
        return this;
    }

    /**
     * NOTE: this is optional
     * When contributed, the runtime will track various metrics relating to the runtime which can be accessed via the registry
     *
     * @param metricsService - The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withMetrics(IMqttsnMetricsService metricsService){
        withService(metricsService);
        return this;
    }

    /**
     * The job of the queue processor is to (when requested) interact with a remote contexts' queue, processing
     * the next message from the HEAD of the queue, handling any topic registration, session state, marking
     * messages inflight and finally returning an indicator as to what should happen when the processing of
     * the next message is complete. Upon dealing with the next message, whether successful or not, the processor
     * needs to return an indiction;
     *
     *  REMOVE_PROCESS - The queue is empty and the context no longer needs further processing
     *  BACKOFF_PROCESS - The queue is not empty, come back after a backend to try again. Repeating this return type for the same context
     *                      will yield an exponential backoff
     *  REPROCESS (continue) - The queue is not empty, (where possible) call me back immediately to process again
     *
     * @param queueProcessor - The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withQueueProcessor(IMqttsnMessageQueueProcessor queueProcessor){
        withService(queueProcessor);
        return this;
    }


    /**
     * Provide a topic modifier to manipulate topics before the enter the system runtime to allow for custom prefixing
     * or placeholders.
     *
     * @param topicModifier - The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withTopicModifier(IMqttsnTopicModifier topicModifier){
        withService(topicModifier);
        return this;
    }

    /**
     * A context factory deals with the initial construction of the context objects which identity
     * the remote connection to the application. There are 2 types of context; a {@link NetworkContext}
     * and a {@link MqttsnContext}. The network context identifies where (the network location) the identity
     * resides and the mqttsn-context identifies who the context is (generally this is the CliendId or GatewayId of
     * the connected resource).
     *
     * A {@link NetworkContext} can exist in isolation without an associated {@link MqttsnContext}, during a CONNECT attempt
     *  (when the context has yet to be established), or during a failed CONNECTion. An application context cannot exist without
     * a network context.
     *
     * You can provide your own implementation, if you wish to wrap or provide your own extending context implementation
     * to wrap custom User objects, for example.
     *
     * @param contextFactory - The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withContextFactory(IMqttsnContextFactory contextFactory){
        withService(contextFactory);
        return this;
    }

    /**
     * The message registry is a normalised view of transiting messages, it context the raw payload of publish operations
     * so lightweight references to the payload can exist in multiple storage systems without duplication of data.
     * For example, when running in gateway mode, the same message my reside in queues for numerous devices which are
     * in different connection states. We should not store payloads N times in this case.
     *
     * The lookup is a simple UUID -> byte[] relationship. It is up to the registry implementation to decide how to store
     * this data.
     *
     * @param messageRegistry The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withMessageRegistry(IMqttsnMessageRegistry messageRegistry){
        withService(messageRegistry);
        return this;
    }

    /**
     * The state service is responsible for sending messages and processing received messages. It maintains state
     * and tracks messages in and out and their successful acknowledgement (or not).
     *
     * The message handling layer will call into the state service with messages it has received, and the queue processor
     * will use the state service to dispatch new outbound publish messages.
     *
     * @param messageStateService The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withMessageStateService(IMqttsnMessageStateService messageStateService){
        withService(messageStateService);
        return this;
    }

    /**
     * The subscription registry maintains a list of subscriptions against the remote context. On the gateway this
     * is used to determine which clients are subscribed to which topics to enable outbound delivery. In client
     * mode it tracks the subscriptions a client presently holds.
     *
     * @param subscriptionRegistry The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withSubscriptionRegistry(IMqttsnSubscriptionRegistry subscriptionRegistry){
        withService(subscriptionRegistry);
        return this;
    }

    /**
     * The topic registry is responsible for tracking, storing and determining the correct alias
     * to use for a given remote context and topic combination. The topic registry will be cleared
     * according to session lifecycle rules.
     *
     * @param topicRegistry The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withTopicRegistry(IMqttsnTopicRegistry topicRegistry){
        withService(topicRegistry);
        return this;
    }

    /**
     * Queue implementation to store messages destined to and from gateways and clients. Queues will be flushed acccording
     * to the session semantics defined during CONNECT.
     *
     * Ideally the queue should be implemented to support FIFO where possible.
     *
     * @param messageQueue The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withMessageQueue(IMqttsnMessageQueue messageQueue){
        withService(messageQueue);
        return this;
    }

    /**
     * A codec contains all the functionality to marshall and unmarshall
     * wire traffic in the format specified by the implementation. Further,
     * it also provides a message factory instance which allows construction
     * of wire messages hiding the underlying transport format. This allows versioned
     * protocol support.
     *
     * @param codec The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withCodec(IMqttsnCodec codec){
        this.codec = codec;
        return this;
    }

    /**
     * The message handler is delegated to by the transport layer and its job is to process
     * inbound messages and marshall into other controllers to manage state lifecycle, authentication, permission
     * etc. It is directly responsible for creating response messages and sending them back to the transport layer.
     *
     * @param handler The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withMessageHandler(IMqttsnMessageHandler handler){
        withService(handler);
        return this;
    }

    /**
     * The transport layer is responsible for managing the receiving and sending of messages over some connection.
     * No session is assumed by the application and the connection is considered stateless at this point.
     * It is envisaged implementations will include UDP (with and without DTLS), TCP-IP (with and without TLS),
     * BLE and ZigBee.
     *
     * Please refer to {@link AbstractMqttsnTransport} and sub-class your own implementations
     * or choose an existing implementation out of the box.
     *
     * @see {@link MqttsnUdpTransport} for an example of an out of the box implementation.
     *
     * @param transport The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withTransport(IMqttsnTransport transport){
        withService(transport);
        return this;
    }

    /**
     * The network registry maintains a list of known network contexts against a remote address ({@link NetworkAddress}).
     * It exposes functionality to wait for discovered contexts as well as returning a list of valid broadcast addresses.
     *
     * @param networkAddressRegistry The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withNetworkAddressRegistry(INetworkAddressRegistry networkAddressRegistry){
        this.networkAddressRegistry = networkAddressRegistry;
        return this;
    }

    /**
     * Provide security services to the runtime
     *
     * @param securityService The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withSecurityService(IMqttsnSecurityService securityService){
        withService(securityService);
        return this;
    }

    /**
     * Provide sessionFactory services to the runtime
     *
     * @param sessionRegistry The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withSessionRegistry(IMqttsnSessionRegistry sessionRegistry){
        withService(sessionRegistry);
        return this;
    }

    /**
     * Optional - when installed it will be consulted to determine whether a remote context can perform certain
     * operations;
     *
     * CONNECT with the given clientId
     *
     * @param authenticationService The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withAuthenticationService(IMqttsnAuthenticationService authenticationService){
        withService(authenticationService);
        return this;
    }

    /**
     * Optional - when installed it will be consulted to determine whether a remote context can perform certain
     * operations;
     *
     * CONNECT with the given clientId
     *
     * @param authorizationService The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withAuthorizationService(IMqttsnAuthorizationService authorizationService){
        withService(authorizationService);
        return this;
    }

    /**
     * A clientId factory allows the runtime to generate clientIds in a format
     * that the application needs. For example, UUID, tokens.
     *
     * A clientId will be passed through the installed factory to resolve a 'final' clientId
     * inside the state system. For example '636c69656e744964' maybe passed over the wire in a CONNECT
     * message but the factory may resolve this to a 'clientId' for use in the application. (This is the string 'clientId' encoded to HEX).
     *
     * This allows for opaque token usage on the protocol message layer
     *
     * @param clientIdFactory The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withClientIdFactory(IMqttsnClientIdFactory clientIdFactory){
        withService(clientIdFactory);
        return this;
    }

    /**
     * The will registry holds the last will and testament data for against a given context. This will be used
     * by the runtime to intialise a session (in client mode) or to notify a topic (in gateway mode)
     *
     * @param willRegistry The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withWillRegistry(IMqttsnWillRegistry willRegistry){
        withService(willRegistry);
        return this;
    }

    /**
     * The dead letter queue to use for undeliverable application messages
     *
     * @param deadLetterQueue The instance
     * @return This runtime registry
     */
    public AbstractMqttsnRuntimeRegistry withDeadLetterQueue(IMqttsnDeadLetterQueue deadLetterQueue){
        withService(deadLetterQueue);
        return this;
    }

    @Override
    public MqttsnOptions getOptions() {
        return options;
    }

    @Override
    public INetworkAddressRegistry getNetworkRegistry() {
        return networkAddressRegistry;
    }

    @Override
    public void setRuntime(AbstractMqttsnRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public AbstractMqttsnRuntime getRuntime() {
        return runtime;
    }

    @Override
    public IMqttsnAuthenticationService getAuthenticationService() {
        return getOptionalService(IMqttsnAuthenticationService.class).orElse(null);
    }

    @Override
    public IMqttsnAuthorizationService getAuthorizationService() {
        return getOptionalService(IMqttsnAuthorizationService.class).orElse(null);
    }

    public IMqttsnQueueProcessorStateService getQueueProcessorStateCheckService() {
        return getOptionalService(IMqttsnQueueProcessorStateService.class).orElse(null);
    }

    @Override
    public IMqttsnCodec getCodec() {
        return codec;
    }

    @Override
    public IMqttsnMessageHandler getMessageHandler() {
        return getService(IMqttsnMessageHandler.class);
    }

    @Override
    public IMqttsnTransport getTransport() {
        return getService(IMqttsnTransport.class);
    }

    @Override
    public IMqttsnMessageFactory getMessageFactory(){
        return factory;
    }

    @Override
    public IMqttsnMessageQueue getMessageQueue() {
        return getService(IMqttsnMessageQueue.class);
    }

    @Override
    public IMqttsnTopicRegistry getTopicRegistry() {
        return getService(IMqttsnTopicRegistry.class);
    }

    @Override
    public IMqttsnSubscriptionRegistry getSubscriptionRegistry() {
        return getService(IMqttsnSubscriptionRegistry.class);
    }

    @Override
    public IMqttsnMessageStateService getMessageStateService() {
        return getService(IMqttsnMessageStateService.class);
    }

    @Override
    public IMqttsnMessageRegistry getMessageRegistry(){
        return getService(IMqttsnMessageRegistry.class);
    }

    @Override
    public IMqttsnWillRegistry getWillRegistry(){
        return getService(IMqttsnWillRegistry.class);
    }

    @Override
    public IMqttsnContextFactory getContextFactory() {
        return getService(IMqttsnContextFactory.class);
    }

    @Override
    public IMqttsnMessageQueueProcessor getQueueProcessor() {
        return getService(IMqttsnMessageQueueProcessor.class);
    }

    @Override
    public IMqttsnSecurityService getSecurityService() {
        return getOptionalService(IMqttsnSecurityService.class).orElse(null);
    }

    @Override
    public IMqttsnSessionRegistry getSessionRegistry() {
        return getService(IMqttsnSessionRegistry.class);
    }

    @Override
    public IMqttsnTopicModifier getTopicModifier() {
        return getOptionalService(IMqttsnTopicModifier.class).orElse(null);
    }

    @Override
    public IMqttsnMetricsService getMetrics() {
        return getOptionalService(IMqttsnMetricsService.class).orElse(null);
    }

    @Override
    public IMqttsnStorageService getStorageService() {
        return storageService;
    }

    @Override
    public IMqttsnClientIdFactory getClientIdFactory() {
        return getService(IMqttsnClientIdFactory.class);
    }

    @Override
    public IMqttsnDeadLetterQueue getDeadLetterQueue() {
        return getOptionalService(IMqttsnDeadLetterQueue.class).orElse(null);
    }

    @Override
    public List<IMqttsnService> getServices() {
        synchronized (services){
            ArrayList sorted = new ArrayList(services);
            Collections.sort(sorted, new ServiceSort());
            return Collections.unmodifiableList(sorted);
        }
    }

    @Override
    public AbstractMqttsnRuntimeRegistry withService(IMqttsnService service){
        synchronized (services){
            services.add(service);
        }
        return this;
    }

    @Override
    public <T extends IMqttsnService> T getService(Class<T> clz){
        synchronized (services){
            return (T) services.stream().filter(s ->
                            clz.isAssignableFrom(s.getClass())).
                    findFirst().orElseThrow(() -> new MqttsnRuntimeException("unable to find service on runtime " + clz.getName()));
        }
    }

    @Override
    public <T extends IMqttsnService> Optional<T> getOptionalService(Class<T> clz){
        synchronized (services){
            return (Optional<T>) services.stream().filter(s ->
                            clz.isAssignableFrom(s.getClass())).findFirst();
        }
    }


    protected void validateOnStartup() throws MqttsnRuntimeException {
        if(storageService == null) throw new MqttsnRuntimeException("storage service must be found for a valid runtime");
        if(networkAddressRegistry == null) throw new MqttsnRuntimeException("network-registry must be bound for valid runtime");
        if(codec == null) throw new MqttsnRuntimeException("codec must be bound for valid runtime");
    }
}