/*
 * Copyright © 2019-2022, 2024 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.grpc.protoc;

import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.DescriptorProtos.SourceCodeInfo;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;

import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static com.squareup.javapoet.TypeName.BOOLEAN;
import static com.squareup.javapoet.TypeSpec.anonymousClassBuilder;
import static com.squareup.javapoet.TypeSpec.classBuilder;
import static com.squareup.javapoet.TypeSpec.interfaceBuilder;
import static io.servicetalk.grpc.protoc.Generator.NewRpcMethodFlag.BLOCKING;
import static io.servicetalk.grpc.protoc.Generator.NewRpcMethodFlag.CLIENT;
import static io.servicetalk.grpc.protoc.Generator.NewRpcMethodFlag.INTERFACE;
import static io.servicetalk.grpc.protoc.Generator.NewRpcMethodFlag.SERVER_RESPONSE;
import static io.servicetalk.grpc.protoc.NoopServiceCommentsMap.NOOP_MAP;
import static io.servicetalk.grpc.protoc.StringUtils.escapeJavaDoc;
import static io.servicetalk.grpc.protoc.StringUtils.sanitizeIdentifier;
import static io.servicetalk.grpc.protoc.Types.AllGrpcRoutes;
import static io.servicetalk.grpc.protoc.Types.Arrays;
import static io.servicetalk.grpc.protoc.Types.AsyncCloseable;
import static io.servicetalk.grpc.protoc.Types.BlockingClientCall;
import static io.servicetalk.grpc.protoc.Types.BlockingGrpcClient;
import static io.servicetalk.grpc.protoc.Types.BlockingGrpcService;
import static io.servicetalk.grpc.protoc.Types.BlockingIterable;
import static io.servicetalk.grpc.protoc.Types.BlockingRequestStreamingClientCall;
import static io.servicetalk.grpc.protoc.Types.BlockingRequestStreamingRoute;
import static io.servicetalk.grpc.protoc.Types.BlockingResponseStreamingClientCall;
import static io.servicetalk.grpc.protoc.Types.BlockingResponseStreamingRoute;
import static io.servicetalk.grpc.protoc.Types.BlockingRoute;
import static io.servicetalk.grpc.protoc.Types.BlockingStreamingClientCall;
import static io.servicetalk.grpc.protoc.Types.BlockingStreamingGrpcServerResponse;
import static io.servicetalk.grpc.protoc.Types.BlockingStreamingRoute;
import static io.servicetalk.grpc.protoc.Types.BufferDecoderGroup;
import static io.servicetalk.grpc.protoc.Types.BufferEncoderList;
import static io.servicetalk.grpc.protoc.Types.ClientCall;
import static io.servicetalk.grpc.protoc.Types.Collections;
import static io.servicetalk.grpc.protoc.Types.Completable;
import static io.servicetalk.grpc.protoc.Types.ContentCodec;
import static io.servicetalk.grpc.protoc.Types.DefaultGrpcClientMetadata;
import static io.servicetalk.grpc.protoc.Types.EmptyBufferDecoderGroup;
import static io.servicetalk.grpc.protoc.Types.GrpcBindableService;
import static io.servicetalk.grpc.protoc.Types.GrpcClient;
import static io.servicetalk.grpc.protoc.Types.GrpcClientCallFactory;
import static io.servicetalk.grpc.protoc.Types.GrpcClientFactory;
import static io.servicetalk.grpc.protoc.Types.GrpcClientMetadata;
import static io.servicetalk.grpc.protoc.Types.GrpcExecutionContext;
import static io.servicetalk.grpc.protoc.Types.GrpcExecutionStrategy;
import static io.servicetalk.grpc.protoc.Types.GrpcMethodDescriptor;
import static io.servicetalk.grpc.protoc.Types.GrpcMethodDescriptorCollection;
import static io.servicetalk.grpc.protoc.Types.GrpcMethodDescriptors;
import static io.servicetalk.grpc.protoc.Types.GrpcPayloadWriter;
import static io.servicetalk.grpc.protoc.Types.GrpcRouteExecutionStrategyFactory;
import static io.servicetalk.grpc.protoc.Types.GrpcRoutes;
import static io.servicetalk.grpc.protoc.Types.GrpcSerializationProvider;
import static io.servicetalk.grpc.protoc.Types.GrpcService;
import static io.servicetalk.grpc.protoc.Types.GrpcServiceContext;
import static io.servicetalk.grpc.protoc.Types.GrpcServiceFactory;
import static io.servicetalk.grpc.protoc.Types.GrpcStatus;
import static io.servicetalk.grpc.protoc.Types.GrpcStatusCode;
import static io.servicetalk.grpc.protoc.Types.GrpcStatusException;
import static io.servicetalk.grpc.protoc.Types.GrpcSupportedCodings;
import static io.servicetalk.grpc.protoc.Types.Identity;
import static io.servicetalk.grpc.protoc.Types.Objects;
import static io.servicetalk.grpc.protoc.Types.ProtoBufSerializationProviderBuilder;
import static io.servicetalk.grpc.protoc.Types.ProtobufSerializerFactory;
import static io.servicetalk.grpc.protoc.Types.Publisher;
import static io.servicetalk.grpc.protoc.Types.RequestStreamingClientCall;
import static io.servicetalk.grpc.protoc.Types.RequestStreamingRoute;
import static io.servicetalk.grpc.protoc.Types.ResponseStreamingClientCall;
import static io.servicetalk.grpc.protoc.Types.ResponseStreamingRoute;
import static io.servicetalk.grpc.protoc.Types.Route;
import static io.servicetalk.grpc.protoc.Types.RouteExecutionStrategy;
import static io.servicetalk.grpc.protoc.Types.RouteExecutionStrategyFactory;
import static io.servicetalk.grpc.protoc.Types.Single;
import static io.servicetalk.grpc.protoc.Types.StreamingClientCall;
import static io.servicetalk.grpc.protoc.Types.StreamingRoute;
import static io.servicetalk.grpc.protoc.Words.ASYNC_METHOD_DESCRIPTORS;
import static io.servicetalk.grpc.protoc.Words.BLOCKING_METHOD_DESCRIPTORS;
import static io.servicetalk.grpc.protoc.Words.Blocking;
import static io.servicetalk.grpc.protoc.Words.Builder;
import static io.servicetalk.grpc.protoc.Words.COMMENT_POST_TAG;
import static io.servicetalk.grpc.protoc.Words.COMMENT_PRE_TAG;
import static io.servicetalk.grpc.protoc.Words.Call;
import static io.servicetalk.grpc.protoc.Words.Client;
import static io.servicetalk.grpc.protoc.Words.Default;
import static io.servicetalk.grpc.protoc.Words.Factory;
import static io.servicetalk.grpc.protoc.Words.INSTANCE;
import static io.servicetalk.grpc.protoc.Words.JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT;
import static io.servicetalk.grpc.protoc.Words.JAVADOC_DEPRECATED;
import static io.servicetalk.grpc.protoc.Words.JAVADOC_PARAM;
import static io.servicetalk.grpc.protoc.Words.JAVADOC_RETURN;
import static io.servicetalk.grpc.protoc.Words.JAVADOC_THROWS;
import static io.servicetalk.grpc.protoc.Words.Metadata;
import static io.servicetalk.grpc.protoc.Words.PATH;
import static io.servicetalk.grpc.protoc.Words.PROTOBUF;
import static io.servicetalk.grpc.protoc.Words.PROTO_CONTENT_TYPE;
import static io.servicetalk.grpc.protoc.Words.Rpc;
import static io.servicetalk.grpc.protoc.Words.Service;
import static io.servicetalk.grpc.protoc.Words.To;
import static io.servicetalk.grpc.protoc.Words.addBlockingService;
import static io.servicetalk.grpc.protoc.Words.addService;
import static io.servicetalk.grpc.protoc.Words.asBlockingClient;
import static io.servicetalk.grpc.protoc.Words.bind;
import static io.servicetalk.grpc.protoc.Words.bufferDecoderGroup;
import static io.servicetalk.grpc.protoc.Words.bufferEncoders;
import static io.servicetalk.grpc.protoc.Words.builder;
import static io.servicetalk.grpc.protoc.Words.client;
import static io.servicetalk.grpc.protoc.Words.close;
import static io.servicetalk.grpc.protoc.Words.closeAsync;
import static io.servicetalk.grpc.protoc.Words.closeAsyncGracefully;
import static io.servicetalk.grpc.protoc.Words.closeGracefully;
import static io.servicetalk.grpc.protoc.Words.closeable;
import static io.servicetalk.grpc.protoc.Words.ctx;
import static io.servicetalk.grpc.protoc.Words.executionContext;
import static io.servicetalk.grpc.protoc.Words.factory;
import static io.servicetalk.grpc.protoc.Words.handle;
import static io.servicetalk.grpc.protoc.Words.initSerializationProvider;
import static io.servicetalk.grpc.protoc.Words.isSupportedMessageCodingsEmpty;
import static io.servicetalk.grpc.protoc.Words.javaMethodName;
import static io.servicetalk.grpc.protoc.Words.metadata;
import static io.servicetalk.grpc.protoc.Words.methodDescriptor;
import static io.servicetalk.grpc.protoc.Words.methodDescriptors;
import static io.servicetalk.grpc.protoc.Words.onClose;
import static io.servicetalk.grpc.protoc.Words.onClosing;
import static io.servicetalk.grpc.protoc.Words.registerRoutes;
import static io.servicetalk.grpc.protoc.Words.request;
import static io.servicetalk.grpc.protoc.Words.requestEncoding;
import static io.servicetalk.grpc.protoc.Words.response;
import static io.servicetalk.grpc.protoc.Words.responseWriter;
import static io.servicetalk.grpc.protoc.Words.route;
import static io.servicetalk.grpc.protoc.Words.routeExecutionStrategyFactory;
import static io.servicetalk.grpc.protoc.Words.routes;
import static io.servicetalk.grpc.protoc.Words.rpc;
import static io.servicetalk.grpc.protoc.Words.service;
import static io.servicetalk.grpc.protoc.Words.strategy;
import static io.servicetalk.grpc.protoc.Words.strategyFactory;
import static io.servicetalk.grpc.protoc.Words.supportedMessageCodings;
import static io.servicetalk.grpc.protoc.Words.timeout;
import static java.lang.System.lineSeparator;
import static java.util.EnumSet.noneOf;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.DEFAULT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PROTECTED;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

final class Generator {
    private static final int RPC_METHOD_NAME_LENGTH_GUESS = 16;

    private static final class RpcInterface {
        final MethodDescriptorProto methodProto;
        final boolean blocking;
        final ClassName className;

        private RpcInterface(final MethodDescriptorProto methodProto, final boolean blocking,
                             final ClassName className) {
            this.methodProto = methodProto;
            this.blocking = blocking;
            this.className = className;
        }
    }

    private static final class ClientMetaData {
        final MethodDescriptorProto methodProto;
        final ClassName className;

        private ClientMetaData(final MethodDescriptorProto methodProto, final ClassName className) {
            this.methodProto = methodProto;
            this.className = className;
        }
    }

    /**
     * Manages state for code generation of a single service
     */
    private static final class State {
        final ServiceDescriptorProto serviceProto;
        final int serviceIndex;

        final List<RpcInterface> serviceRpcInterfaces;
        final ClassName serviceClass;
        final ClassName blockingServiceClass;
        final ClassName serviceFactoryClass;

        final List<ClientMetaData> clientMetaDatas;
        final ClassName clientClass;
        final ClassName blockingClientClass;

        private State(ServiceDescriptorProto serviceProto, GenerationContext context, String outerClassName,
                      int serviceIndex) {
            this.serviceProto = serviceProto;
            this.serviceIndex = serviceIndex;

            final String sanitizedProtoName = sanitizeIdentifier(serviceProto.getName(), false);

            // Filled in during addServiceRpcInterfaces()
            serviceRpcInterfaces = new ArrayList<>(2 * serviceProto.getMethodCount());
            serviceClass = ClassName.bestGuess(context.deconflictJavaTypeName(outerClassName,
                    sanitizedProtoName + Service));
            blockingServiceClass = serviceClass.peerClass(Blocking + serviceClass.simpleName());
            serviceFactoryClass = serviceClass.peerClass(Service + Factory);

            // Filled in during addClientMetadata()
            clientMetaDatas = new ArrayList<>(serviceProto.getMethodCount());
            clientClass = ClassName.bestGuess(context.deconflictJavaTypeName(outerClassName,
                    sanitizedProtoName + Client));
            blockingClientClass = clientClass.peerClass(Blocking + clientClass.simpleName());
        }
    }

    private final GenerationContext context;
    private final Map<String, ClassName> messageTypesMap;
    private final ServiceCommentsMap serviceCommentsMap;
    private final boolean printJavaDocs;
    private final boolean skipDeprecated;
    private final boolean defaultServiceMethods;

    Generator(final GenerationContext context, final Map<String, ClassName> messageTypesMap,
              final boolean printJavaDocs, final boolean skipDeprecated, final boolean defaultServiceMethods,
              SourceCodeInfo sourceCodeInfo) {
        this.context = context;
        this.messageTypesMap = messageTypesMap;
        this.serviceCommentsMap = printJavaDocs ? new DefaultServiceCommentsMap(sourceCodeInfo) : NOOP_MAP;
        this.printJavaDocs = printJavaDocs;
        this.skipDeprecated = skipDeprecated;
        this.defaultServiceMethods = defaultServiceMethods;
    }

    /**
     * Generate Service class for the provided proto service descriptor.
     *
     * @param serviceProto The service descriptor.
     * @param serviceIndex The index of the service within the current file (0 based).
     * @return The service class builder
     */
    TypeSpec.Builder generate(FileDescriptor f, final ServiceDescriptorProto serviceProto, final int serviceIndex) {
        final ServiceClassBuilder container = context.newServiceClassBuilder(serviceProto);
        final State state = new State(serviceProto, context, container.className, serviceIndex);
        if (printJavaDocs) {
            container.builder.addJavadoc("Class for $L Service", serviceProto.getName());
        }

        addSerializationProviderInit(state, container.builder);

        addServiceRpcInterfaces(state, container.builder);
        addServiceInterfaces(state, container.builder);
        addServiceFactory(state, container.builder);

        addClientMetadata(state, container.builder);

        addClientInterfaces(state, container.builder);
        addClientFactory(state, container.builder);
        // this empty class is a placeholder and get replaced with insertion point comment
        container.builder.addType(TypeSpec.classBuilder("__" + serviceFQN(f, serviceProto)).build());

        return container.builder;
    }

    private String serviceFQN(FileDescriptor f, ServiceDescriptorProto serviceDescriptorProto) {
        return f.getProtoPackageName() != null ?
                f.getProtoPackageName() + "." + serviceDescriptorProto.getName() :
                serviceDescriptorProto.getName();
    }

    private TypeSpec.Builder addSerializationProviderInit(final State state,
                                                          final TypeSpec.Builder serviceClassBuilder) {
        final CodeBlock.Builder staticInitBlockBuilder = CodeBlock.builder()
                // TODO: Cache serializationProvider for each set of encoding types
                .addStatement("$T builder = new $T()", ProtoBufSerializationProviderBuilder,
                        ProtoBufSerializationProviderBuilder)
                .addStatement("builder.supportedMessageCodings($L)", supportedMessageCodings);

        concat(state.serviceProto.getMethodList().stream()
                        .filter(MethodDescriptorProto::hasInputType)
                        .map(MethodDescriptorProto::getInputType),
                state.serviceProto.getMethodList().stream()
                        .filter(MethodDescriptorProto::hasOutputType)
                        .map(MethodDescriptorProto::getOutputType))
                .distinct()
                .map(messageTypesMap::get)
                .forEach(t -> staticInitBlockBuilder.addStatement("$L.registerMessageType($T.class, $T.parser())",
                        builder, t, t));

        staticInitBlockBuilder
                .addStatement("return $L.build()", builder)
                .build();

        if (!skipDeprecated) {
            serviceClassBuilder
                    .addMethod(methodBuilder(initSerializationProvider)
                            .addModifiers(PRIVATE, STATIC)
                            .returns(GrpcSerializationProvider)
                            .addParameter(GrpcSupportedCodings, supportedMessageCodings, FINAL)
                            .addCode(staticInitBlockBuilder.build())
                            .build()
                    )
                    .addMethod(methodBuilder(isSupportedMessageCodingsEmpty)
                            .addModifiers(PRIVATE, STATIC)
                            .returns(BOOLEAN)
                            .addParameter(GrpcSupportedCodings, supportedMessageCodings, FINAL)
                            .addStatement("return $L.isEmpty() || ($L.size() == 1 && $T.identity().equals($L.get(0)))",
                                    supportedMessageCodings, supportedMessageCodings, Identity, supportedMessageCodings)
                            .build());
        }

        return serviceClassBuilder;
    }

    private static FieldSpec newMethodDescriptorSpec(
            final ClassName inClass, final ClassName outClass, final String javaMethodName,
            final boolean clientStreaming, final boolean serverStreaming, final String methodHttpPath,
            final ParameterizedTypeName methodDescriptorType, final String methodDescFieldName, final boolean isAsync) {
        return FieldSpec.builder(methodDescriptorType, methodDescFieldName)
                .addModifiers(PRIVATE, STATIC, FINAL)
                .initializer("$T.newMethodDescriptor($S, $S, $L, $L, $T.class, $S, " +
                                "$T.$L.serializerDeserializer($T.parser()), $T::getSerializedSize, $L, $L, " +
                                "$T.class, $S, $T.$L.serializerDeserializer($T.parser()), $T::getSerializedSize)",
                        GrpcMethodDescriptors, methodHttpPath, javaMethodName,
                        clientStreaming, clientStreaming && isAsync, inClass, PROTO_CONTENT_TYPE,
                        ProtobufSerializerFactory, PROTOBUF, inClass, inClass,
                        serverStreaming, isAsync, outClass, PROTO_CONTENT_TYPE,
                        ProtobufSerializerFactory, PROTOBUF, outClass, outClass).build();
    }

    private String addServiceRpcInterfaceSpec(final State state,
                                              final TypeSpec.Builder serviceClassBuilder,
                                              final MethodDescriptorProto methodProto,
                                              final int methodIndex, final boolean isAsync) {
        final String name = context.deconflictJavaTypeName(isAsync ?
                sanitizeIdentifier(methodProto.getName(), false) + Rpc :
                Blocking + sanitizeIdentifier(methodProto.getName(), false) + Rpc);
        final String methodDescFieldName = name + "_MD";
        final ClassName inClass = messageTypesMap.get(methodProto.getInputType());
        final ClassName outClass = messageTypesMap.get(methodProto.getOutputType());
        final ParameterizedTypeName methodDescriptorType =
                ParameterizedTypeName.get(GrpcMethodDescriptor, inClass, outClass);
        final String methodHttpPath = context.methodPath(state.serviceProto, methodProto);
        final String javaMethodName = routeName(methodProto);
        serviceClassBuilder.addField(newMethodDescriptorSpec(inClass, outClass, javaMethodName,
                methodProto.getClientStreaming(), methodProto.getServerStreaming(), methodHttpPath,
                methodDescriptorType, methodDescFieldName, isAsync));

        final TypeSpec.Builder interfaceSpecBuilder = interfaceBuilder(name)
                .addAnnotation(FunctionalInterface.class)
                .addModifiers(PUBLIC)
                .addSuperinterface(isAsync ? GrpcService : BlockingGrpcService)
                .addMethod(methodBuilder(methodDescriptor)
                        .addModifiers(PUBLIC, STATIC)
                        .returns(methodDescriptorType)
                        .addStatement("return $L", methodDescFieldName).build());

        final MethodSpec methodSpec = newRpcMethodSpec(inClass, outClass, javaMethodName,
                methodProto.getClientStreaming(), methodProto.getServerStreaming(),
                isAsync ? EnumSet.of(INTERFACE) : EnumSet.of(INTERFACE, BLOCKING),
                printJavaDocs, (__, b) -> {
                    b.addModifiers(ABSTRACT).addParameter(GrpcServiceContext, ctx);
                    if (printJavaDocs) {
                        extractJavaDocComments(state, methodIndex, b);
                        b.addJavadoc(JAVADOC_PARAM + ctx +
                                " context associated with this service and request." + lineSeparator());
                    }
                    return b;
                });

        final boolean isDeprecated = methodSpec.annotations.contains(AnnotationSpec.builder(Deprecated.class).build());
        if (!skipDeprecated) {
            interfaceSpecBuilder.addMethod(methodSpec);
        } else {
            if (!isDeprecated) {
                interfaceSpecBuilder.addMethod(methodSpec);
            }
        }

        if (!skipDeprecated) {
            final FieldSpec.Builder pathSpecBuilder = FieldSpec.builder(String.class, PATH)
                    .addJavadoc(JAVADOC_DEPRECATED + "Use {@link #$L}." + lineSeparator(), methodDescriptor)
                    .addAnnotation(Deprecated.class)
                    .addModifiers(PUBLIC, STATIC, FINAL) // redundant, default for interface field
                    .initializer("$S", context.methodPath(state.serviceProto, methodProto));
            interfaceSpecBuilder.addField(pathSpecBuilder.build());
        }

        if (!isAsync && methodProto.getServerStreaming()) {
            interfaceSpecBuilder.addMethod(newRpcMethodSpec(inClass, outClass, javaMethodName,
                    methodProto.getClientStreaming(), methodProto.getServerStreaming(),
                    EnumSet.of(INTERFACE, BLOCKING, SERVER_RESPONSE), printJavaDocs,
                    (__, b) -> {
                        if (isDeprecated && skipDeprecated) {
                            b.addModifiers(ABSTRACT).addParameter(GrpcServiceContext, ctx);
                        } else {
                            b.addModifiers(DEFAULT).addParameter(GrpcServiceContext, ctx);
                            if (printJavaDocs) {
                                extractJavaDocComments(state, methodIndex, b);
                                b.addJavadoc(JAVADOC_PARAM + ctx +
                                        " context associated with this service and request." + lineSeparator());
                            }
                            b.addStatement("$L($L, $L, $L.sendMetaData())", javaMethodName, ctx, request, response);
                        }
                        return b;
                    }));
        }

        if (methodProto.hasOptions() && methodProto.getOptions().getDeprecated()) {
            interfaceSpecBuilder.addAnnotation(Deprecated.class);
        }

        state.serviceRpcInterfaces.add(new RpcInterface(methodProto, !isAsync, ClassName.bestGuess(name)));
        serviceClassBuilder.addType(interfaceSpecBuilder.build());
        return methodDescFieldName;
    }

    private TypeSpec.Builder addServiceRpcInterfaces(final State state, final TypeSpec.Builder serviceClassBuilder) {
        List<MethodDescriptorProto> methodDescriptorProtoList = state.serviceProto.getMethodList();
        StringBuilder asyncMethodDescriptors =
                new StringBuilder(methodDescriptorProtoList.size() * RPC_METHOD_NAME_LENGTH_GUESS);
        StringBuilder blockingMethodDescriptors =
                new StringBuilder(methodDescriptorProtoList.size() * RPC_METHOD_NAME_LENGTH_GUESS);
        for (int i = 0; i < methodDescriptorProtoList.size(); ++i) {
            MethodDescriptorProto methodProto = methodDescriptorProtoList.get(i);
            asyncMethodDescriptors
                    .append(addServiceRpcInterfaceSpec(state, serviceClassBuilder, methodProto, i, true)).append(", ");
            blockingMethodDescriptors
                    .append(addServiceRpcInterfaceSpec(state, serviceClassBuilder, methodProto, i, false)).append(", ");
        }

        serviceClassBuilder
                .addField(setInitializerList(FieldSpec.builder(GrpcMethodDescriptorCollection, ASYNC_METHOD_DESCRIPTORS)
                        .addModifiers(PRIVATE, STATIC, FINAL), asyncMethodDescriptors)
                        .build())
                .addField(setInitializerList(FieldSpec.builder(GrpcMethodDescriptorCollection,
                                BLOCKING_METHOD_DESCRIPTORS)
                        .addModifiers(PRIVATE, STATIC, FINAL), blockingMethodDescriptors)
                        .build());

        return serviceClassBuilder;
    }

    private static FieldSpec.Builder setInitializerList(FieldSpec.Builder builder, StringBuilder sb) {
        if (sb.length() == 0) {
            builder.initializer("$T.emptyList()", Collections);
        } else {
            builder.initializer("$T.unmodifiableList($T.asList($L))", Collections, Arrays,
                    sb.substring(0, sb.length() - 2));
        }
        return builder;
    }

    private void extractJavaDocComments(State state, int methodIndex, MethodSpec.Builder b) {
        String serviceComments = serviceCommentsMap.getLeadingComments(state.serviceIndex, methodIndex);
        if (serviceComments != null) {
            StringBuilder sb = new StringBuilder(serviceComments.length() * 2);
            sb.append(COMMENT_PRE_TAG).append(lineSeparator());
            escapeJavaDoc(serviceComments, sb);
            sb.append(COMMENT_POST_TAG).append(lineSeparator()).append(lineSeparator());
            b.addJavadoc(sb.toString());
        }
    }

    /**
     * Define interfaces for the async and blocking Service which
     * will extend all of the appropriate RPC interfaces
     *
     * @param state the generator state
     * @param serviceClassBuilder the target service class builder for the service interfaces
     * @return the service class builder
     */
    private TypeSpec.Builder addServiceInterfaces(final State state, final TypeSpec.Builder serviceClassBuilder) {
        return serviceClassBuilder
                .addType(newServiceInterfaceSpec(state, false))
                .addType(newServiceInterfaceSpec(state, true));
    }

    /**
     * Add the ServiceFactory class
     *
     * @param state the generator state
     * @param serviceClassBuilder the target service class builder for the service factory interfaces
     * @return the service class builder
     */
    private TypeSpec.Builder addServiceFactory(final State state, final TypeSpec.Builder serviceClassBuilder) {
        final ClassName builderClass = state.serviceFactoryClass.nestedClass(Builder);
        final ClassName serviceFromRoutesClass = builderClass.nestedClass(
                state.serviceClass.simpleName() + "FromRoutes");

        // TODO: Warn for path override and Validate all paths are defined.
        final TypeSpec.Builder serviceBuilderSpecBuilder = classBuilder(Builder)
                .addModifiers(PUBLIC, STATIC, FINAL)
                .addField(FieldSpec.builder(BufferDecoderGroup, bufferDecoderGroup)
                        .addModifiers(PRIVATE)
                        .initializer("$T.INSTANCE", EmptyBufferDecoderGroup).build())
                .addField(FieldSpec.builder(BufferEncoderList, bufferEncoders)
                        .addModifiers(PRIVATE)
                        .initializer("$T.emptyList()", Collections).build())
                .superclass(ParameterizedTypeName.get(GrpcRoutes, state.serviceClass))
                .addMethod(methodBuilder(bufferDecoderGroup)
                        .addModifiers(PUBLIC)
                        .addParameter(BufferDecoderGroup, bufferDecoderGroup, FINAL)
                        .returns(builderClass)
                        .addStatement("this.$L = $T.requireNonNull($L)", bufferDecoderGroup, Objects,
                                bufferDecoderGroup)
                        .addStatement("return this").build())
                .addMethod(methodBuilder(bufferEncoders)
                        .addModifiers(PUBLIC)
                        .addParameter(BufferEncoderList, bufferEncoders, FINAL)
                        .returns(builderClass)
                        .addStatement("this.$L = $T.requireNonNull($L)", bufferEncoders, Objects, bufferEncoders)
                        .addStatement("return this").build())
                .addMethod(methodBuilder(routeExecutionStrategyFactory)
                        .addModifiers(PUBLIC)
                        .addAnnotation(Override.class)
                        .addParameter(GrpcRouteExecutionStrategyFactory, strategyFactory, FINAL)
                        .returns(builderClass)
                        .addStatement("super.$L($L)", routeExecutionStrategyFactory, strategyFactory)
                        .addStatement("return this").build())
                .addMethod(methodBuilder("build")
                        .addModifiers(PUBLIC)
                        .returns(state.serviceFactoryClass)
                        .addStatement("return new $T(this)", state.serviceFactoryClass)
                        .build());

        if (!skipDeprecated) {
            serviceBuilderSpecBuilder.addField(FieldSpec.builder(GrpcSupportedCodings, supportedMessageCodings)
                            .addModifiers(PRIVATE, FINAL).build())
                    .addType(newServiceFromRoutesClassSpec(serviceFromRoutesClass, state.serviceRpcInterfaces,
                            state.serviceClass))
                    .addMethod(constructorBuilder()
                            .addJavadoc(JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT)
                            .addModifiers(PUBLIC)
                            .addStatement("this($T.emptyList())", Collections)
                            .build())
                    .addMethod(constructorBuilder()
                            .addJavadoc(JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT)
                            .addJavadoc(lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + supportedMessageCodings + " the set of allowed encodings" +
                                    lineSeparator())
                            .addJavadoc(JAVADOC_DEPRECATED + "Use {@link #$L($T)} and {@link #$L($T)}." +
                                            lineSeparator(),
                                    bufferDecoderGroup, BufferDecoderGroup, bufferEncoders, Types.List)
                            .addAnnotation(Deprecated.class)
                            .addModifiers(PUBLIC)
                            .addParameter(GrpcSupportedCodings, supportedMessageCodings, FINAL)
                            .addStatement("this.$L = $L", supportedMessageCodings, supportedMessageCodings)
                            .build())
                    .addMethod(constructorBuilder()
                            .addJavadoc(JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT)
                            .addJavadoc(lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + strategyFactory +
                                    " a factory that creates an execution strategy for different {@link $L#id() id}s" +
                                    lineSeparator(), RouteExecutionStrategy)
                            .addJavadoc(JAVADOC_DEPRECATED + "use {@link #$L($T)} on the Builder instead." +
                                    lineSeparator(), routeExecutionStrategyFactory, RouteExecutionStrategyFactory)
                            .addAnnotation(Deprecated.class)
                            .addModifiers(PUBLIC)
                            .addParameter(GrpcRouteExecutionStrategyFactory, strategyFactory, FINAL)
                            .addStatement("this($L, $T.emptyList())", strategyFactory, Collections)
                            .build())
                    .addMethod(constructorBuilder()
                            .addJavadoc(JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT)
                            .addJavadoc(lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + strategyFactory +
                                    " a factory that creates an execution strategy for different {@link $L#id() id}s" +
                                    lineSeparator(), RouteExecutionStrategy)
                            .addJavadoc(JAVADOC_PARAM + supportedMessageCodings + " the set of allowed encodings" +
                                    lineSeparator())
                            .addJavadoc(JAVADOC_DEPRECATED +
                                            "Use {@link #$L($T)}, {@link #$L($T)}, and {@link #$L($T)}." +
                                            lineSeparator(),
                                    Builder, RouteExecutionStrategyFactory, bufferDecoderGroup, BufferDecoderGroup,
                                    bufferEncoders, Types.List)
                            .addAnnotation(Deprecated.class)
                            .addModifiers(PUBLIC)
                            .addParameter(GrpcRouteExecutionStrategyFactory, strategyFactory, FINAL)
                            .addParameter(GrpcSupportedCodings, supportedMessageCodings, FINAL)
                            .addStatement("super($L)", strategyFactory)
                            .addStatement("this.$L = $L", supportedMessageCodings, supportedMessageCodings)
                            .build())
                    .addMethod(methodBuilder("newServiceFromRoutes")
                            .addModifiers(PROTECTED)
                            .addAnnotation(Override.class)
                            .addAnnotation(Deprecated.class)
                            .returns(serviceFromRoutesClass)
                            .addParameter(AllGrpcRoutes, routes, FINAL)
                            .addStatement("return new $T($L)", serviceFromRoutesClass, routes)
                            .build());
        }

        state.serviceRpcInterfaces.forEach(rpcInterface -> {
            final ClassName inClass = messageTypesMap.get(rpcInterface.methodProto.getInputType());
            final ClassName outClass = messageTypesMap.get(rpcInterface.methodProto.getOutputType());
            final String routeName = routeName(rpcInterface.methodProto);
            final String methodName = routeName + (rpcInterface.blocking ? Blocking : "");
            final String addRouteMethodName = addRouteMethodName(rpcInterface.methodProto, rpcInterface.blocking);

            CodeBlock.Builder addRouteCodeBuilder = CodeBlock.builder()
                    .add(routeDefinition(rpcInterface, routeName, inClass, outClass));

            if (!skipDeprecated) {
                addRouteCodeBuilder.beginControlFlow("if ($L.isEmpty())", supportedMessageCodings)
                        .addStatement("$L($L.getClass(), $T.$L(), $L, $L, $L)",
                                addRouteMethodName, rpc,
                                rpcInterface.className, methodDescriptor, bufferDecoderGroup, bufferEncoders,
                                route).nextControlFlow("else")
                        .addStatement("$L($T.$L, $L.getClass(), $T.$L().$L(), $L, $T.class, $T.class, $L($L))",
                                addRouteMethodName, rpcInterface.className, PATH, rpc,
                                rpcInterface.className, methodDescriptor, javaMethodName,
                                route,
                                inClass, outClass, initSerializationProvider, supportedMessageCodings)
                        .endControlFlow();
            } else {
                addRouteCodeBuilder.addStatement("$L($L.getClass(), $T.$L(), $L, $L, $L)",
                        addRouteMethodName, rpc,
                        rpcInterface.className, methodDescriptor, bufferDecoderGroup, bufferEncoders,
                        route);
            }

            CodeBlock addRouteCode = addRouteCodeBuilder.build();

            CodeBlock.Builder addRouteExecCodeBuilder = CodeBlock.builder()
                    .add(routeDefinition(rpcInterface, routeName, inClass, outClass));

            if (!skipDeprecated) {
                addRouteExecCodeBuilder.beginControlFlow("if ($L.isEmpty())", supportedMessageCodings)
                        .addStatement("$L($L, $T.$L(), $L, $L, $L)",
                                addRouteMethodName, strategy,
                                rpcInterface.className, methodDescriptor, bufferDecoderGroup, bufferEncoders,
                                route)
                        .nextControlFlow("else")
                        .addStatement("$L($T.$L, $L, $L, $T.class, $T.class, $L($L))",
                                addRouteMethodName, rpcInterface.className, PATH, strategy,
                                route,
                                inClass, outClass, initSerializationProvider, supportedMessageCodings)
                        .endControlFlow();
            } else {
                addRouteExecCodeBuilder.addStatement("$L($L, $T.$L(), $L, $L, $L)",
                        addRouteMethodName, strategy,
                        rpcInterface.className, methodDescriptor, bufferDecoderGroup, bufferEncoders,
                        route);
            }

            CodeBlock addRouteExecCode = addRouteExecCodeBuilder.build();

            serviceBuilderSpecBuilder
                    .addMethod(methodBuilder(methodName)
                            .addModifiers(PUBLIC)
                            .addParameter(rpcInterface.className, rpc, FINAL)
                            .returns(builderClass)
                            .addStatement("$T.requireNonNull($L)", Objects, rpc)
                            .addCode(addRouteCode)
                            .addStatement("return this")
                            .build())
                    .addMethod(methodBuilder(methodName)
                            .addModifiers(PUBLIC)
                            .addParameter(GrpcExecutionStrategy, strategy, FINAL)
                            .addParameter(rpcInterface.className, rpc, FINAL)
                            .returns(builderClass)
                            .addStatement("$T.requireNonNull($L)", Objects, strategy)
                            .addStatement("$T.requireNonNull($L)", Objects, rpc)
                            .addCode(addRouteExecCode)
                            .addStatement("return this")
                            .build());
        });

        final MethodSpec.Builder addServiceBuilder = methodBuilder(addService)
                .addModifiers(PUBLIC)
                .returns(builderClass)
                .addParameter(state.serviceClass, service, FINAL)
                .addStatement("$T.requireNonNull($L)", Objects, service);

        if (!skipDeprecated) {
            serviceBuilderSpecBuilder.addMethod(methodBuilder(addService)
                    .addModifiers(PUBLIC)
                    .addAnnotation(Deprecated.class)
                    .addJavadoc("Adds a {@link $T} implementation." + lineSeparator(), state.blockingServiceClass)
                    .addJavadoc(lineSeparator())
                    .addJavadoc(JAVADOC_PARAM + service + " the {@link $T} implementation to add." + lineSeparator(),
                            state.blockingServiceClass)
                    .addJavadoc(JAVADOC_RETURN + "this." + lineSeparator())
                    .addJavadoc(JAVADOC_DEPRECATED + "Use {@link #$L($L)}." + lineSeparator(),
                            // force canonicalName to work around JDK8 erroneous javadoc
                            // `warning - Tag @link: can't find`
                            addBlockingService, state.blockingServiceClass.canonicalName())
                    .returns(builderClass)
                    .addParameter(state.blockingServiceClass, service, FINAL)
                    .addStatement("return $L($L)", addBlockingService, service)
                    .build());
        }

        final MethodSpec.Builder addBlockingServiceMethodSpecBuilder = methodBuilder(addBlockingService)
                .addModifiers(PUBLIC)
                .returns(builderClass)
                .addParameter(state.blockingServiceClass, service, FINAL)
                .addStatement("$T.requireNonNull($L)", Objects, service);

        final MethodSpec.Builder registerRoutesMethodSpecBuilder = methodBuilder(registerRoutes)
                .addModifiers(PROTECTED)
                .addAnnotation(Override.class)
                .addAnnotation(Deprecated.class)    // FIXME: 0.43 - remove deprecated method
                .addParameter(state.serviceClass, service, FINAL);

        state.serviceProto.getMethodList().stream()
                .map(Generator::routeName)
                .forEach(n -> {
                    if (!skipDeprecated) {
                        registerRoutesMethodSpecBuilder.addStatement("$L($L)", n, service);
                    }
                    addServiceBuilder.addStatement("$L($L)", n, service);
                    addBlockingServiceMethodSpecBuilder.addStatement("$L$L($L)", n, Blocking, service);
                });

        addServiceBuilder.addStatement("return this");
        serviceBuilderSpecBuilder.addMethod(addServiceBuilder.build());
        addBlockingServiceMethodSpecBuilder.addStatement("return this");

        final TypeSpec.Builder serviceBuilder = serviceBuilderSpecBuilder
                .addMethod(addBlockingServiceMethodSpecBuilder.build());

        if (!skipDeprecated) {
            serviceBuilder.addMethod(registerRoutesMethodSpecBuilder.build());
        }

        final TypeSpec serviceBuilderType = serviceBuilder.build();

        final TypeSpec.Builder serviceFactoryClassSpecBuilder = classBuilder(state.serviceFactoryClass)
                .addModifiers(PUBLIC, STATIC, FINAL)
                .superclass(ParameterizedTypeName.get(GrpcServiceFactory, state.serviceClass))
                // Add ServiceFactory constructors for blocking and async services with and without content codings and
                // execution strategy
                .addMethod(constructorBuilder()
                        .addJavadoc(JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT)
                        .addJavadoc(lineSeparator())
                        .addJavadoc(JAVADOC_PARAM + service + " a service to handle incoming requests" +
                                lineSeparator())
                        .addModifiers(PUBLIC)
                        .addParameter(state.serviceClass, service, FINAL)
                        .addStatement("this(new $T().$L)", builderClass,
                                serviceFactoryBuilderInitChain(state.serviceProto, false))
                        .build())
                .addMethod(constructorBuilder()
                        .addJavadoc(JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT)
                        .addJavadoc(lineSeparator())
                        .addJavadoc(JAVADOC_PARAM + service + " a service to handle incoming requests" +
                                lineSeparator())
                        .addModifiers(PUBLIC)
                        .addParameter(state.blockingServiceClass, service, FINAL)
                        .addStatement("this(new $T().$L)", builderClass,
                                serviceFactoryBuilderInitChain(state.serviceProto, true))
                        .build())
                // and the private constructor they all call
                .addMethod(constructorBuilder()
                        .addModifiers(PRIVATE)
                        .addParameter(builderClass, builder, FINAL)
                        .addStatement("super($L)", builder)
                        .build())
                .addType(serviceBuilderType);

        if (!skipDeprecated) {
            serviceFactoryClassSpecBuilder.addMethod(constructorBuilder()
                            .addJavadoc(JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT)
                            .addJavadoc(lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + service + " a service to handle incoming requests" +
                                    lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + supportedMessageCodings + " the set of allowed encodings" +
                                    lineSeparator())
                            .addJavadoc(JAVADOC_DEPRECATED +
                                            "Use {@link $L#$L()}, {@link $L#$L($T)}, and {@link $L#$L($T)}." +
                                            lineSeparator(),
                                    Builder, Builder, Builder, bufferDecoderGroup, BufferDecoderGroup,
                                    Builder, bufferEncoders, Types.List)
                            .addAnnotation(Deprecated.class)
                            .addModifiers(PUBLIC)
                            .addParameter(state.serviceClass, service, FINAL)
                            .addParameter(GrpcSupportedCodings, supportedMessageCodings, FINAL)
                            .addStatement("this(new $T($L).$L)", builderClass, supportedMessageCodings,
                                    serviceFactoryBuilderInitChain(state.serviceProto, false))
                            .build())
                    .addMethod(constructorBuilder()
                            .addJavadoc(JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT)
                            .addJavadoc(lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + service + " a service to handle incoming requests" +
                                    lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + strategyFactory +
                                    " a factory that creates an execution strategy for different {@link $L#id() id}s" +
                                    lineSeparator(), RouteExecutionStrategy)
                            .addJavadoc(JAVADOC_DEPRECATED + "Use {@link $L#$L()} and set the custom strategy " +
                                            "on {@link $L#$L($T)} instead." + lineSeparator(), Builder, Builder,
                                    Builder, routeExecutionStrategyFactory, RouteExecutionStrategyFactory)
                            .addAnnotation(Deprecated.class)
                            .addModifiers(PUBLIC)
                            .addParameter(state.serviceClass, service, FINAL)
                            .addParameter(GrpcRouteExecutionStrategyFactory, strategyFactory, FINAL)
                            .addStatement("this(new $T($L).$L)", builderClass, strategyFactory,
                                    serviceFactoryBuilderInitChain(state.serviceProto, false))
                            .build())
                    .addMethod(constructorBuilder()
                            .addJavadoc(JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT)
                            .addJavadoc(lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + service + " a service to handle incoming requests" +
                                    lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + strategyFactory +
                                    " a factory that creates an execution strategy for different {@link $L#id() id}s" +
                                    lineSeparator(), RouteExecutionStrategy)
                            .addJavadoc(JAVADOC_PARAM + supportedMessageCodings + " the set of allowed encodings" +
                                    lineSeparator())
                            .addJavadoc(JAVADOC_DEPRECATED +
                                            "Use {@link $L#$L($T)}, {@link $L#$L($T)}, and {@link $L#$L($T)}." +
                                            lineSeparator(),
                                    Builder, Builder, RouteExecutionStrategyFactory, Builder, bufferDecoderGroup,
                                    BufferDecoderGroup, Builder, bufferEncoders, Types.List)
                            .addAnnotation(Deprecated.class)
                            .addModifiers(PUBLIC)
                            .addParameter(state.serviceClass, service, FINAL)
                            .addParameter(GrpcRouteExecutionStrategyFactory, strategyFactory, FINAL)
                            .addParameter(GrpcSupportedCodings, supportedMessageCodings, FINAL)
                            .addStatement("this(new $T($L, $L).$L)", builderClass, strategyFactory,
                                    supportedMessageCodings, serviceFactoryBuilderInitChain(state.serviceProto,
                                            false))
                            .build())
                    .addMethod(constructorBuilder()
                            .addJavadoc(JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT)
                            .addJavadoc(lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + service + " a service to handle incoming requests" +
                                    lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + supportedMessageCodings + " the set of allowed encodings" +
                                    lineSeparator())
                            .addJavadoc(JAVADOC_DEPRECATED +
                                            "Use {@link $L#$L()}, {@link $L#$L($T)}, and {@link $L#$L($T)}." +
                                            lineSeparator(),
                                    Builder, Builder, Builder, bufferDecoderGroup, BufferDecoderGroup,
                                    Builder, bufferEncoders, Types.List)
                            .addAnnotation(Deprecated.class)
                            .addModifiers(PUBLIC)
                            .addParameter(state.blockingServiceClass, service, FINAL)
                            .addParameter(GrpcSupportedCodings, supportedMessageCodings, FINAL)
                            .addStatement("this(new $T($L).$L)", builderClass, supportedMessageCodings,
                                    serviceFactoryBuilderInitChain(state.serviceProto, true))
                            .build())
                    .addMethod(constructorBuilder()
                            .addJavadoc(JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT)
                            .addJavadoc(lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + service + " a service to handle incoming requests" +
                                    lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + strategyFactory +
                                    " a factory that creates an execution strategy for different {@link $L#id() id}s" +
                                    lineSeparator(), RouteExecutionStrategy)
                            .addJavadoc(JAVADOC_DEPRECATED + "Use {@link $L#$L()} and set the custom strategy " +
                                            "on {@link $L#$L($T)} instead." + lineSeparator(), Builder, Builder,
                                    Builder, routeExecutionStrategyFactory, RouteExecutionStrategyFactory)
                            .addAnnotation(Deprecated.class)
                            .addModifiers(PUBLIC)
                            .addParameter(state.blockingServiceClass, service, FINAL)
                            .addParameter(GrpcRouteExecutionStrategyFactory, strategyFactory, FINAL)
                            .addStatement("this(new $T($L).$L)", builderClass, strategyFactory,
                                    serviceFactoryBuilderInitChain(state.serviceProto, true))
                            .build())
                    .addMethod(constructorBuilder()
                            .addJavadoc(JAVADOC_CONSTRUCTOR_DEFAULT_STATEMENT)
                            .addJavadoc(lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + service + " a service to handle incoming requests" +
                                    lineSeparator())
                            .addJavadoc(JAVADOC_PARAM + strategyFactory +
                                    " a factory that creates an execution strategy for different {@link $L#id() id}s" +
                                    lineSeparator(), RouteExecutionStrategy)
                            .addJavadoc(JAVADOC_PARAM + supportedMessageCodings + " the set of allowed encodings" +
                                    lineSeparator())
                            .addJavadoc(JAVADOC_DEPRECATED +
                                            "Use {@link $L#$L($T)}, {@link $L#$L($T)}, and {@link $L#$L($T)}." +
                                            lineSeparator(),
                                    Builder, Builder, RouteExecutionStrategyFactory, Builder, bufferDecoderGroup,
                                    BufferDecoderGroup, Builder, bufferEncoders, Types.List)
                            .addAnnotation(Deprecated.class)
                            .addModifiers(PUBLIC)
                            .addParameter(state.blockingServiceClass, service, FINAL)
                            .addParameter(GrpcRouteExecutionStrategyFactory, strategyFactory, FINAL)
                            .addParameter(GrpcSupportedCodings, supportedMessageCodings, FINAL)
                            .addStatement("this(new $T($L, $L).$L)", builderClass, strategyFactory,
                                    supportedMessageCodings, serviceFactoryBuilderInitChain(state.serviceProto, true))
                            .build());
        }

        serviceClassBuilder.addType(serviceFactoryClassSpecBuilder.build());

        return serviceClassBuilder;
    }

    private TypeSpec.Builder addClientMetadata(final State state, final TypeSpec.Builder serviceClassBuilder) {
        state.serviceRpcInterfaces.stream().filter(rpcInterface -> !rpcInterface.blocking).forEach(rpcInterface -> {
            MethodDescriptorProto methodProto = rpcInterface.methodProto;
            final String name = context.deconflictJavaTypeName(sanitizeIdentifier(methodProto.getName(), false) +
                    Metadata);

            final ClassName metaDataClassName = ClassName.bestGuess(name);
            if (!skipDeprecated) {
                final TypeSpec.Builder classSpecBuilder = classBuilder(name)
                        .addJavadoc(JAVADOC_DEPRECATED + "This class will be removed in the future in favor of direct "
                                +
                                "usage of {@link $T}. Deprecation of {@link $T#path()} renders this type unnecessary."
                                + lineSeparator(), GrpcClientMetadata, GrpcClientMetadata)
                        .addAnnotation(Deprecated.class)
                        .addModifiers(PUBLIC, STATIC, FINAL)
                        .superclass(DefaultGrpcClientMetadata)
                        .addField(FieldSpec.builder(metaDataClassName, INSTANCE)
                                .addJavadoc(JAVADOC_DEPRECATED + "Use {@link $T}." + lineSeparator(),
                                        DefaultGrpcClientMetadata)
                                .addAnnotation(Deprecated.class)
                                .addModifiers(PUBLIC, STATIC, FINAL) // redundant, default for interface field
                                .initializer("new $T()", metaDataClassName)
                                .build())
                        .addMethod(constructorBuilder()
                                .addModifiers(PRIVATE)
                                .addParameter(GrpcClientMetadata, metadata, FINAL)
                                .addStatement("super($T.$L, $L)", rpcInterface.className, PATH, metadata)
                                .build())
                        .addMethod(constructorBuilder()
                                .addModifiers(PRIVATE)
                                .addStatement("super($T.$L)", rpcInterface.className, PATH)
                                .build())
                        .addMethod(constructorBuilder()
                                .addModifiers(PUBLIC)
                                .addParameter(GrpcExecutionStrategy, strategy, FINAL)
                                .addStatement("super($T.$L, $L)", rpcInterface.className, PATH, strategy)
                                .build())
                        .addMethod(constructorBuilder()
                                .addModifiers(PUBLIC)
                                .addParameter(Duration.class, timeout, FINAL)
                                .addStatement("super($T.$L, $L)", rpcInterface.className, PATH, timeout)
                                .build())
                        .addMethod(constructorBuilder()
                                .addModifiers(PUBLIC)
                                .addParameter(ContentCodec, requestEncoding, FINAL)
                                .addStatement("super($T.$L, $L)", rpcInterface.className, PATH, requestEncoding)
                                .build())
                        .addMethod(constructorBuilder()
                                .addModifiers(PUBLIC)
                                .addParameter(GrpcExecutionStrategy, strategy, FINAL)
                                .addParameter(ContentCodec, requestEncoding, FINAL)
                                .addStatement("super($T.$L, $L, $L)", rpcInterface.className, PATH,
                                        strategy, requestEncoding)
                                .build())
                        .addMethod(constructorBuilder()
                                .addModifiers(PUBLIC)
                                .addParameter(GrpcExecutionStrategy, strategy, FINAL)
                                .addParameter(ContentCodec, requestEncoding, FINAL)
                                .addParameter(Duration.class, timeout, FINAL)
                                .addStatement("super($T.$L, $L, $L, $L)", rpcInterface.className, PATH,
                                        strategy, requestEncoding, timeout)
                                .build());
                TypeSpec classSpec = classSpecBuilder.build();
                serviceClassBuilder.addType(classSpec);
            }

            state.clientMetaDatas.add(new ClientMetaData(methodProto, metaDataClassName));
        });

        return serviceClassBuilder;
    }

    private TypeSpec.Builder addClientInterfaces(final State state, final TypeSpec.Builder serviceClassBuilder) {
        final TypeSpec.Builder clientSpecBuilder = interfaceBuilder(state.clientClass)
                .addModifiers(PUBLIC)
                .addSuperinterface(ParameterizedTypeName.get(GrpcClient, state.blockingClientClass));

        final TypeSpec.Builder blockingClientSpecBuilder = interfaceBuilder(state.blockingClientClass)
                .addModifiers(PUBLIC)
                .addSuperinterface(ParameterizedTypeName.get(BlockingGrpcClient, state.clientClass));

        for (int i = 0; i < state.clientMetaDatas.size(); ++i) {
            final int methodIndex = i;
            ClientMetaData clientMetaData = state.clientMetaDatas.get(i);
            clientSpecBuilder
                    .addMethod(newRpcMethodSpec(clientMetaData.methodProto, EnumSet.of(INTERFACE, CLIENT),
                            printJavaDocs, (__, b) -> {
                                b.addModifiers(ABSTRACT);
                                if (printJavaDocs) {
                                    extractJavaDocComments(state, methodIndex, b);
                                }
                                return b;
                            }));

            if (!skipDeprecated) {
                clientSpecBuilder
                        .addMethod(newRpcMethodSpec(clientMetaData.methodProto, EnumSet.of(INTERFACE, CLIENT),
                                printJavaDocs, (methodName, b) -> {
                                    b.addModifiers(DEFAULT).addParameter(GrpcClientMetadata, metadata);
                                    if (printJavaDocs) {
                                        extractJavaDocComments(state, methodIndex, b);
                                        b.addJavadoc(JAVADOC_PARAM + metadata +
                                                " the metadata associated with this client call." + lineSeparator());
                                    }
                                    return b.addStatement("return $L(new $T($L), $L)", methodName,
                                            clientMetaData.className, metadata, request);
                                }))
                        .addMethod(newRpcMethodSpec(clientMetaData.methodProto, EnumSet.of(INTERFACE, CLIENT),
                                printJavaDocs, (methodName, b) -> {
                                    ClassName inClass = messageTypesMap.get(clientMetaData.methodProto.getInputType());
                                    b.addModifiers(DEFAULT).addParameter(clientMetaData.className, metadata)
                                            .addAnnotation(Deprecated.class);
                                    if (printJavaDocs) {
                                        extractJavaDocComments(state, methodIndex, b);
                                        b.addJavadoc(JAVADOC_DEPRECATED + "Use {@link #$L($T,$T)}." + lineSeparator(),
                                                        methodName, GrpcClientMetadata,
                                                        clientMetaData.methodProto.getClientStreaming() ?
                                                                Publisher : inClass)
                                                .addJavadoc(JAVADOC_PARAM + metadata +
                                                        " the metadata associated with this client call." +
                                                        lineSeparator());
                                    }
                                    return b.addStatement("return $T.failed(new UnsupportedOperationException(\"" +
                                            "This method is not implemented by \" + getClass() + \". " +
                                            "Consider migrating " +
                                            "Consider migrating " +
                                            "to an alternative method or implement this method if it's required " +
                                            "temporarily.\"))", clientMetaData.methodProto.getServerStreaming() ?
                                            Publisher : Single);
                                }));
            } else {
                clientSpecBuilder.addMethod(newRpcMethodSpec(clientMetaData.methodProto, EnumSet.of(INTERFACE, CLIENT),
                        printJavaDocs, (methodName, b) -> {
                            b.addModifiers(ABSTRACT).addParameter(GrpcClientMetadata, metadata);
                            if (printJavaDocs) {
                                extractJavaDocComments(state, methodIndex, b);
                                b.addJavadoc(JAVADOC_PARAM + metadata +
                                        " the metadata associated with this client call." + lineSeparator());
                            }
                            return b;
                        }));
            }

            blockingClientSpecBuilder
                    .addMethod(newRpcMethodSpec(clientMetaData.methodProto, EnumSet.of(BLOCKING, INTERFACE, CLIENT),
                            printJavaDocs, (__, b) -> {
                                b.addModifiers(ABSTRACT);
                                if (printJavaDocs) {
                                    extractJavaDocComments(state, methodIndex, b);
                                }
                                return b;
                            }));

            if (!skipDeprecated) {
                blockingClientSpecBuilder.addMethod(newRpcMethodSpec(clientMetaData.methodProto,
                                EnumSet.of(BLOCKING, INTERFACE, CLIENT),
                                printJavaDocs, (methodName, b) -> {
                                    ClassName inClass = messageTypesMap.get(clientMetaData.methodProto.getInputType());
                                    b.addModifiers(DEFAULT).addParameter(clientMetaData.className, metadata)
                                            .addAnnotation(Deprecated.class);
                                    if (printJavaDocs) {
                                        extractJavaDocComments(state, methodIndex, b);
                                        b.addJavadoc(JAVADOC_DEPRECATED + "Use {@link #$L($T,$T)}." + lineSeparator(),
                                                        methodName, GrpcClientMetadata,
                                                        clientMetaData.methodProto.getClientStreaming() ?
                                                                Types.Iterable :
                                                                inClass)
                                                .addJavadoc(JAVADOC_PARAM + metadata +
                                                        " the metadata associated with this client call." +
                                                        lineSeparator());
                                    }
                                    return b.addStatement("throw new UnsupportedOperationException(\"" +
                                            "This method is not " +
                                            "implemented by \" + getClass() + \". Consider " +
                                            "migrating to an alternative " +
                                            "method or implement this method if it's required temporarily.\")");
                                }
                        ))
                        .addMethod(newRpcMethodSpec(clientMetaData.methodProto,
                                EnumSet.of(BLOCKING, INTERFACE, CLIENT),
                                printJavaDocs, (methodName, b) -> {
                                    b.addModifiers(DEFAULT).addParameter(GrpcClientMetadata, metadata);
                                    if (printJavaDocs) {
                                        extractJavaDocComments(state, methodIndex, b);
                                        b.addJavadoc(JAVADOC_PARAM + metadata +
                                                " the metadata associated with this client call." + lineSeparator());
                                    }
                                    return b.addStatement("return $L(new $T($L), $L)", methodName,
                                            clientMetaData.className, metadata, request);
                                }
                        ));
            } else {
                blockingClientSpecBuilder.addMethod(newRpcMethodSpec(clientMetaData.methodProto,
                        EnumSet.of(BLOCKING, INTERFACE, CLIENT),
                        printJavaDocs, (methodName, b) -> {
                            b.addModifiers(ABSTRACT).addParameter(GrpcClientMetadata, metadata);
                            if (printJavaDocs) {
                                extractJavaDocComments(state, methodIndex, b);
                                b.addJavadoc(JAVADOC_PARAM + metadata +
                                        " the metadata associated with this client call." + lineSeparator());
                            }
                            return b;
                        }
                ));
            }
        }

        final ClassName clientToBlockingClientClass = state.clientClass.peerClass(state.clientClass.simpleName() + To
                + state.blockingClientClass.simpleName());

        clientSpecBuilder.addMethod(methodBuilder(asBlockingClient)
                .addModifiers(PUBLIC, DEFAULT)
                .addAnnotation(Override.class)
                .returns(state.blockingClientClass)
                .addStatement("return new $T(this)", clientToBlockingClientClass).build());

        serviceClassBuilder.addType(clientSpecBuilder.build()).addType(blockingClientSpecBuilder.build())
                .addType(newClientToBlockingClientClassSpec(state, clientToBlockingClientClass));

        return serviceClassBuilder;
    }

    private TypeSpec.Builder addClientFactory(final State state, final TypeSpec.Builder serviceClassBuilder) {
        final ClassName clientFactoryClass = state.clientClass.peerClass(Client + Factory);
        final ClassName defaultClientClass = clientFactoryClass.nestedClass(Default + state.clientClass.simpleName());
        final ClassName defaultBlockingClientClass = clientFactoryClass.nestedClass(Default +
                state.blockingClientClass.simpleName());

        final MethodSpec.Builder newClientMethodBuilder = methodBuilder("newClient")
                .addModifiers(PROTECTED)
                .addAnnotation(Override.class)
                .returns(state.clientClass)
                .addParameter(GrpcClientCallFactory, factory, FINAL);

        if (!skipDeprecated) {
            newClientMethodBuilder.addStatement("return new $T($L, $L(), $L())", defaultClientClass, factory,
                    supportedMessageCodings, bufferDecoderGroup);
        } else {
            newClientMethodBuilder.addStatement("return new $T($L, $L())", defaultClientClass, factory,
                    bufferDecoderGroup);
        }

        final MethodSpec.Builder newBlockingClientMethodBuilder = methodBuilder("newBlockingClient")
                .addModifiers(PROTECTED)
                .addAnnotation(Override.class)
                .returns(state.blockingClientClass)
                .addParameter(GrpcClientCallFactory, factory, FINAL);

        if (!skipDeprecated) {
            newBlockingClientMethodBuilder.addStatement("return new $T($L, $L(), $L())", defaultBlockingClientClass,
                    factory, supportedMessageCodings, bufferDecoderGroup);
        } else {
            newBlockingClientMethodBuilder.addStatement("return new $T($L, $L())", defaultBlockingClientClass,
                    factory, bufferDecoderGroup);
        }

        final TypeSpec.Builder clientFactorySpecBuilder = classBuilder(clientFactoryClass)
                .addModifiers(PUBLIC, STATIC)
                .superclass(ParameterizedTypeName.get(GrpcClientFactory, state.clientClass, state.blockingClientClass))
                .addMethod(newClientMethodBuilder.build())
                .addMethod(newBlockingClientMethodBuilder.build())
                .addType(newDefaultClientClassSpec(state, defaultClientClass, defaultBlockingClientClass))
                .addType(newDefaultBlockingClientClassSpec(state, defaultClientClass, defaultBlockingClientClass));

        serviceClassBuilder.addType(clientFactorySpecBuilder.build());

        return serviceClassBuilder;
    }

    // FIXME: 0.43 - remove deprecated class
    private TypeSpec newServiceFromRoutesClassSpec(final ClassName serviceFromRoutesClass,
                                                   final List<RpcInterface> rpcInterfaces,
                                                   final ClassName serviceClass) {
        final TypeSpec.Builder serviceFromRoutesSpecBuilder = classBuilder(serviceFromRoutesClass)
                .addModifiers(PRIVATE, STATIC, FINAL)
                .addSuperinterface(serviceClass)
                .addAnnotation(Deprecated.class)
                .addField(AsyncCloseable, closeable, PRIVATE, FINAL);

        final MethodSpec.Builder serviceFromRoutesConstructorBuilder = constructorBuilder()
                .addModifiers(PRIVATE)
                .addParameter(AllGrpcRoutes, routes, FINAL)
                .addStatement("$L = $L", closeable, routes);

        rpcInterfaces.stream().filter(rpcInterface -> !rpcInterface.blocking).forEach(rpc -> {
            MethodDescriptorProto methodProto = rpc.methodProto;
            final ClassName inClass = messageTypesMap.get(methodProto.getInputType());
            final ClassName outClass = messageTypesMap.get(methodProto.getOutputType());
            final String routeName = routeName(methodProto);

            serviceFromRoutesSpecBuilder.addField(ParameterizedTypeName.get(routeInterfaceClass(methodProto),
                    inClass, outClass), routeName, PRIVATE, FINAL);

            serviceFromRoutesConstructorBuilder.addStatement("$L = $L.$L($T.methodDescriptor().httpPath())",
                    routeName, routes, routeFactoryMethodName(methodProto), rpc.className);

            serviceFromRoutesSpecBuilder.addMethod(newRpcMethodSpec(methodProto, noneOf(NewRpcMethodFlag.class), false,
                    (name, builder) ->
                            builder.addAnnotation(Override.class)
                                    .addParameter(GrpcServiceContext, ctx, FINAL)
                                    .addStatement("return $L.handle($L, $L)", routeName, ctx, request)));
        });

        serviceFromRoutesSpecBuilder
                .addMethod(serviceFromRoutesConstructorBuilder.build())
                .addMethod(newDelegatingCompletableMethodSpec(closeAsync, closeable))
                .addMethod(newDelegatingCompletableMethodSpec(closeAsyncGracefully, closeable));

        return serviceFromRoutesSpecBuilder.build();
    }

    enum NewRpcMethodFlag {
        BLOCKING, INTERFACE, CLIENT, SERVER_RESPONSE
    }

    private MethodSpec newRpcMethodSpec(
            final MethodDescriptorProto methodProto, final EnumSet<NewRpcMethodFlag> flags, final boolean printJavaDocs,
            final BiFunction<String, MethodSpec.Builder, MethodSpec.Builder> methodBuilderCustomizer) {
        return newRpcMethodSpec(messageTypesMap.get(methodProto.getInputType()),
                messageTypesMap.get(methodProto.getOutputType()), routeName(methodProto),
                methodProto.getClientStreaming(), methodProto.getServerStreaming(), flags, printJavaDocs,
                methodBuilderCustomizer);
    }

    private MethodSpec newRpcMethodSpec(
            final ClassName inClass, final ClassName outClass, final String methodName,
            final boolean clientStreaming, final boolean serverStreaming,
            final EnumSet<NewRpcMethodFlag> flags, final boolean printJavaDocs,
            final BiFunction<String, MethodSpec.Builder, MethodSpec.Builder> methodBuilderCustomizer) {
        final MethodSpec.Builder methodSpecBuilder = methodBuilderCustomizer.apply(methodName,
                methodBuilder(methodName)).addModifiers(PUBLIC);

        final Modifier[] mods = flags.contains(INTERFACE) ? new Modifier[0] : new Modifier[]{FINAL};

        if (flags.contains(BLOCKING)) {
            Consumer<MethodSpec.Builder> lastJavadoc = __ -> { /* noop */ };
            if (clientStreaming) {
                if (flags.contains(CLIENT)) {
                    methodSpecBuilder.addParameter(ParameterizedTypeName.get(Types.Iterable, inClass), request, mods);
                    if (printJavaDocs) {
                        methodSpecBuilder.addJavadoc(JAVADOC_PARAM + request +
                                " used to send a stream of type {@link $T} to the server." + lineSeparator(), inClass);
                    }
                } else {
                    methodSpecBuilder.addParameter(ParameterizedTypeName.get(BlockingIterable, inClass), request, mods);
                    if (printJavaDocs) {
                        methodSpecBuilder.addJavadoc(JAVADOC_PARAM + request +
                                " used to read the stream of type {@link $T} from the client." + lineSeparator(),
                                inClass);
                    }
                }
            } else {
                methodSpecBuilder.addParameter(inClass, request, mods);
                if (printJavaDocs) {
                    methodSpecBuilder.addJavadoc(JAVADOC_PARAM + request + " the request from the client." +
                            lineSeparator());
                }
            }

            if (serverStreaming) {
                if (flags.contains(CLIENT)) {
                    methodSpecBuilder.returns(ParameterizedTypeName.get(BlockingIterable, outClass));
                    if (printJavaDocs) {
                        methodSpecBuilder.addJavadoc(
                                JAVADOC_RETURN + "used to read the response stream of type {@link $T} from the server."
                                        + lineSeparator(), outClass);
                    }
                } else if (flags.contains(SERVER_RESPONSE)) {
                    methodSpecBuilder.addParameter(
                            ParameterizedTypeName.get(BlockingStreamingGrpcServerResponse, outClass), response, mods);
                    if (printJavaDocs) {
                        methodSpecBuilder.addJavadoc(JAVADOC_PARAM + response +
                                " used to send response meta-data and continue writing a stream of type {@link $T} " +
                                "to the client." + lineSeparator() +
                                "The implementation of this method is responsible for calling {@link $T#close()}." +
                                lineSeparator(), outClass, GrpcPayloadWriter);
                    }
                } else {
                    methodSpecBuilder.addAnnotation(Deprecated.class);
                    methodSpecBuilder.addParameter(ParameterizedTypeName.get(GrpcPayloadWriter, outClass),
                            responseWriter, mods);
                    if (printJavaDocs) {
                        methodSpecBuilder.addJavadoc(JAVADOC_PARAM + responseWriter +
                                " used to write a stream of type {@link $T} to the client." + lineSeparator() +
                                "The implementation of this method is responsible for calling {@link $T#close()}." +
                                lineSeparator(), outClass, GrpcPayloadWriter);
                        lastJavadoc = b -> b.addJavadoc(JAVADOC_DEPRECATED + "Use {@link #$L($T, $T, $T)}." +
                                        lineSeparator() +
                                        "In the next release, this method will have a default implementation but " +
                                        "the new overload won't." + lineSeparator() +
                                        "To avoid breaking API changes, make sure to implement both methods. The " +
                                        "release after next will remove this method." + lineSeparator() +
                                        "This intermediate step is necessary to maintain {@link FunctionalInterface} " +
                                        "contract that requires to have a single non-default method." + lineSeparator(),
                                methodName, GrpcServiceContext, clientStreaming ? BlockingIterable : inClass,
                                BlockingStreamingGrpcServerResponse);
                    }
                }
            } else {
                methodSpecBuilder.returns(outClass);
                if (printJavaDocs) {
                    methodSpecBuilder.addJavadoc(JAVADOC_RETURN + (flags.contains(CLIENT) ?
                            "the response from the server." :
                            "the response to send to the client") + lineSeparator());
                }
            }
            methodSpecBuilder.addException(Exception.class);
            if (printJavaDocs) {
                methodSpecBuilder.addJavadoc(JAVADOC_THROWS + "$T if an unexpected application error occurs." +
                                lineSeparator(), Exception.class)
                        .addJavadoc(JAVADOC_THROWS +
                                "$T if an expected application exception occurs. Its contents will be serialized and " +
                                "propagated to the peer." + lineSeparator(), GrpcStatusException);
                lastJavadoc.accept(methodSpecBuilder);
            }
        } else {
            if (clientStreaming) {
                methodSpecBuilder.addParameter(ParameterizedTypeName.get(Publisher, inClass), request, mods);
                if (printJavaDocs) {
                    methodSpecBuilder.addJavadoc(JAVADOC_PARAM + request +
                            " used to write a stream of type {@link $T} to the server." + lineSeparator(), inClass);
                }
            } else {
                methodSpecBuilder.addParameter(inClass, request, mods);
                if (printJavaDocs) {
                    methodSpecBuilder.addJavadoc(JAVADOC_PARAM + request +
                            (flags.contains(CLIENT) ?
                                    " the request to send to the server." :
                                    " the request from the client.") + lineSeparator());
                }
            }

            if (serverStreaming) {
                methodSpecBuilder.returns(ParameterizedTypeName.get(Publisher, outClass));
                if (printJavaDocs) {
                    methodSpecBuilder.addJavadoc(JAVADOC_RETURN + (flags.contains(CLIENT) ?
                            "used to read a stream of type {@link $T} from the server." :
                            "used to write a stream of type {@link $T} to the client.")
                            + lineSeparator(), outClass);
                }
            } else {
                methodSpecBuilder.returns(ParameterizedTypeName.get(Single, outClass));
                if (printJavaDocs) {
                    methodSpecBuilder.addJavadoc(JAVADOC_RETURN + (flags.contains(CLIENT) ?
                            "a {@link $T} which completes when the response is received from the server." :
                            "a {@link $T} which sends the response to the client when it terminates.")
                            + lineSeparator(), Single);
                }
            }
        }

        return methodSpecBuilder.build();
    }

    private TypeSpec newDefaultBlockingClientClassSpec(final State state, final ClassName defaultClientClass,
                                                       final ClassName defaultBlockingClientClass) {
        final MethodSpec.Builder asClientMethodBuilder = methodBuilder("asClient")
                .addModifiers(PUBLIC)
                .addAnnotation(Override.class)
                .returns(state.clientClass);

        // TODO: Cache client
        if (!skipDeprecated) {
            asClientMethodBuilder.addStatement("return new $T($L, $L, $L)", defaultClientClass, factory,
                    supportedMessageCodings, bufferDecoderGroup);
        } else {
            asClientMethodBuilder.addStatement("return new $T($L, $L)", defaultClientClass, factory,
                    bufferDecoderGroup);
        }

        final TypeSpec.Builder typeSpecBuilder = classBuilder(defaultBlockingClientClass)
                .addModifiers(PRIVATE, STATIC, FINAL)
                .addSuperinterface(state.blockingClientClass)
                .addField(GrpcClientCallFactory, factory, PRIVATE, FINAL)
                .addField(BufferDecoderGroup, bufferDecoderGroup, PRIVATE, FINAL)
                .addMethod(asClientMethodBuilder.build())
                .addMethod(newDelegatingMethodSpec(executionContext, factory, GrpcExecutionContext, null))
                .addMethod(newDelegatingCompletableToBlockingMethodSpec(close, closeAsync, factory))
                .addMethod(newDelegatingCompletableToBlockingMethodSpec(closeGracefully, closeAsyncGracefully,
                        factory));

        if (!skipDeprecated) {
            typeSpecBuilder.addField(GrpcSupportedCodings, supportedMessageCodings, PRIVATE, FINAL);
        }

        final MethodSpec.Builder constructorBuilder = constructorBuilder()
                .addModifiers(PRIVATE)
                .addParameter(GrpcClientCallFactory, factory, FINAL);

        if (!skipDeprecated) {
            constructorBuilder.addParameter(GrpcSupportedCodings, supportedMessageCodings, FINAL)
                    .addStatement("this.$N = $N", supportedMessageCodings, supportedMessageCodings);
        }

        constructorBuilder.addParameter(BufferDecoderGroup, bufferDecoderGroup, FINAL)
                .addStatement("this.$N = $N", factory, factory)
                .addStatement("this.$N = $N", bufferDecoderGroup, bufferDecoderGroup);

        addClientFieldsAndMethods(state, typeSpecBuilder, constructorBuilder, true);

        typeSpecBuilder.addMethod(constructorBuilder.build());
        return typeSpecBuilder.build();
    }

    private TypeSpec newDefaultClientClassSpec(final State state, final ClassName defaultClientClass,
                                               final ClassName defaultBlockingClientClass) {
        final MethodSpec.Builder asClientMethodBuilder = methodBuilder(asBlockingClient)
                .addModifiers(PUBLIC)
                .addAnnotation(Override.class)
                .returns(state.blockingClientClass);

        if (!skipDeprecated) {
            // TODO: Cache client
            asClientMethodBuilder.addStatement("return new $T($L, $L, $L)", defaultBlockingClientClass,
                    factory, supportedMessageCodings, bufferDecoderGroup);
        } else {
            asClientMethodBuilder.addStatement("return new $T($L, $L)", defaultBlockingClientClass,
                    factory, bufferDecoderGroup);
        }

        final TypeSpec.Builder typeSpecBuilder = classBuilder(defaultClientClass)
                .addModifiers(PRIVATE, STATIC, FINAL)
                .addSuperinterface(state.clientClass)
                .addField(GrpcClientCallFactory, factory, PRIVATE, FINAL)
                .addField(BufferDecoderGroup, bufferDecoderGroup, PRIVATE, FINAL)
                .addMethod(asClientMethodBuilder.build())
                .addMethod(newDelegatingMethodSpec(executionContext, factory, GrpcExecutionContext, null))
                .addMethod(newDelegatingCompletableMethodSpec(onClose, factory))
                .addMethod(newDelegatingCompletableMethodSpec(onClosing, factory))
                .addMethod(newDelegatingCompletableMethodSpec(closeAsync, factory))
                .addMethod(newDelegatingCompletableMethodSpec(closeAsyncGracefully, factory))
                .addMethod(newDelegatingCompletableToBlockingMethodSpec(close, closeAsync, factory))
                .addMethod(newDelegatingCompletableToBlockingMethodSpec(closeGracefully, closeAsyncGracefully,
                        factory));

        if (!skipDeprecated) {
            typeSpecBuilder.addField(GrpcSupportedCodings, supportedMessageCodings, PRIVATE, FINAL);
        }

        final MethodSpec.Builder constructorBuilder = constructorBuilder()
                .addModifiers(PRIVATE)
                .addParameter(GrpcClientCallFactory, factory, FINAL);

        if (!skipDeprecated) {
            constructorBuilder
                    .addParameter(GrpcSupportedCodings, supportedMessageCodings, FINAL)
                    .addStatement("this.$N = $N", supportedMessageCodings, supportedMessageCodings);
        }

        constructorBuilder.addParameter(BufferDecoderGroup, bufferDecoderGroup, FINAL)
                .addStatement("this.$N = $N", factory, factory)
                .addStatement("this.$N = $N", bufferDecoderGroup, bufferDecoderGroup);

        addClientFieldsAndMethods(state, typeSpecBuilder, constructorBuilder, false);

        typeSpecBuilder.addMethod(constructorBuilder.build());
        return typeSpecBuilder.build();
    }

    private void addClientFieldsAndMethods(final State state, final TypeSpec.Builder typeSpecBuilder,
                                           final MethodSpec.Builder constructorBuilder,
                                           final boolean blocking) {

        final EnumSet<NewRpcMethodFlag> rpcMethodSpecsFlags =
                blocking ? EnumSet.of(BLOCKING, CLIENT) : EnumSet.of(CLIENT);

        assert state.clientMetaDatas.size() == state.serviceRpcInterfaces.size() >>> 1;
        for (int i = 0; i < state.clientMetaDatas.size(); ++i) {
            ClientMetaData clientMetaData = state.clientMetaDatas.get(i);
            // clientMetaDatas only contains async methods, serviceRpcInterfaces include async methods then blocking
            // methods.
            RpcInterface rpcInterface = state.serviceRpcInterfaces.get((i << 1) + (blocking ? 1 : 0));
            assert blocking == rpcInterface.blocking;
            final ClassName inClass = messageTypesMap.get(clientMetaData.methodProto.getInputType());
            final ClassName outClass = messageTypesMap.get(clientMetaData.methodProto.getOutputType());
            final String routeName = routeName(clientMetaData.methodProto);
            final String callFieldName = routeName + Call;

            typeSpecBuilder
                    .addField(ParameterizedTypeName.get(clientCallClass(clientMetaData.methodProto, blocking),
                            inClass, outClass), callFieldName, PRIVATE, FINAL)
                    .addMethod(newRpcMethodSpec(clientMetaData.methodProto, rpcMethodSpecsFlags, false,
                            (__, b) -> b.addAnnotation(Override.class)
                                    .addParameter(GrpcClientMetadata, metadata, FINAL)
                                    .addStatement("$T.requireNonNull($L)", Objects, metadata)
                                    .addStatement("$T.requireNonNull($L)", Objects, request)
                                    .addStatement("return $L.$L($L, $L)", callFieldName, request, metadata, request)));

            if (!skipDeprecated) {
                typeSpecBuilder.addMethod(newRpcMethodSpec(clientMetaData.methodProto, rpcMethodSpecsFlags, false,
                        (__, b) -> b.addAnnotation(Deprecated.class)
                                .addAnnotation(Override.class)
                                .addParameter(clientMetaData.className, metadata, FINAL)
                                .addStatement("$T.requireNonNull($L)", Objects, metadata)
                                .addStatement("$T.requireNonNull($L)", Objects, request)
                                .addStatement("return $L.$L($L, $L)", callFieldName, request, metadata, request)))
                        .addMethod(newRpcMethodSpec(clientMetaData.methodProto, rpcMethodSpecsFlags, false,
                                (n, b) -> b.addAnnotation(Override.class)
                                            .addStatement("return $L($L.isEmpty() ? new $T() : new $T(), $L)", n,
                                                    supportedMessageCodings, DefaultGrpcClientMetadata,
                                                    clientMetaData.className, request)));
            } else {
                typeSpecBuilder.addMethod(newRpcMethodSpec(clientMetaData.methodProto, rpcMethodSpecsFlags, false,
                        (n, b) -> b.addAnnotation(Override.class)
                                        .addStatement("return $L(new $T(), $L)",
                                                n, DefaultGrpcClientMetadata, request)));
            }

            CodeBlock.Builder constructorCodeBuilder = CodeBlock.builder();

            if (!skipDeprecated) {
                constructorCodeBuilder.beginControlFlow("if ($L.isEmpty())", supportedMessageCodings)
                        .addStatement("$L = $N.$L($T.$L(), $L)", callFieldName, factory,
                                newCallMethodName(clientMetaData.methodProto, blocking), rpcInterface.className,
                                methodDescriptor, bufferDecoderGroup)
                        .nextControlFlow("else")
                        .addStatement("$L = $N.$L($L($L), $T.class, $T.class)", callFieldName, factory,
                                newCallMethodName(clientMetaData.methodProto, blocking), initSerializationProvider,
                                supportedMessageCodings, inClass, outClass)
                        .endControlFlow();
            } else {
                constructorCodeBuilder.addStatement("$L = $N.$L($T.$L(), $L)", callFieldName, factory,
                        newCallMethodName(clientMetaData.methodProto, blocking), rpcInterface.className,
                        methodDescriptor, bufferDecoderGroup);
            }

            constructorBuilder.addCode(constructorCodeBuilder.build());
        }
    }

    private TypeSpec newClientToBlockingClientClassSpec(final State state,
                                                        final ClassName clientToBlockingClientClass) {
        final TypeSpec.Builder typeSpecBuilder = classBuilder(clientToBlockingClientClass)
                .addModifiers(PRIVATE, STATIC, FINAL)
                .addSuperinterface(state.blockingClientClass)
                .addField(state.clientClass, client, PRIVATE, FINAL)
                .addMethod(constructorBuilder()
                        .addParameter(state.clientClass, client, FINAL)
                        .addStatement("this.$L = $L", client, client)
                        .build())
                .addMethod(methodBuilder("asClient")
                        .addModifiers(PUBLIC)
                        .addAnnotation(Override.class)
                        .returns(state.clientClass)
                        .addStatement("return $L", client)
                        .build())
                .addMethod(newDelegatingMethodSpec(executionContext, client, GrpcExecutionContext, null))
                .addMethod(newDelegatingMethodSpec(close, client, null, ClassName.get(Exception.class)));

        state.clientMetaDatas.forEach(clientMetaData -> {
            final CodeBlock requestExpression = clientMetaData.methodProto.getClientStreaming() ?
                    CodeBlock.of("$T.fromIterable($L)", Publisher, request) : CodeBlock.of(request);
            final String responseConversionExpression = clientMetaData.methodProto.getServerStreaming() ?
                    ".toIterable()" : ".toFuture().get()";

            typeSpecBuilder
                    .addMethod(newRpcMethodSpec(clientMetaData.methodProto, EnumSet.of(BLOCKING, CLIENT), false,
                            (n, b) -> b.addAnnotation(Override.class)
                                    .addStatement("return $L.$L($L)$L", client, n, requestExpression,
                                            responseConversionExpression)))
                    .addMethod(newRpcMethodSpec(clientMetaData.methodProto, EnumSet.of(BLOCKING, CLIENT), false,
                            (n, b) -> b.addAnnotation(Override.class)
                                    .addParameter(GrpcClientMetadata, metadata, FINAL)
                                    .addStatement("return $L.$L($L, $L)$L", client, n, metadata, requestExpression,
                                            responseConversionExpression)));

            if (!skipDeprecated) {
                typeSpecBuilder.addMethod(newRpcMethodSpec(clientMetaData.methodProto, EnumSet.of(BLOCKING, CLIENT),
                        false,
                        (n, b) -> b.addAnnotation(Deprecated.class)
                                .addAnnotation(Override.class)
                                .addParameter(clientMetaData.className, metadata, FINAL)
                                .addStatement("return $L.$L($L, $L)$L", client, n, metadata, requestExpression,
                                        responseConversionExpression)));
            }
        });

        return typeSpecBuilder.build();
    }

    /**
     * Adds the service interface, either async or blocking, which extends all of the service RPC interfaces
     *
     * @param state The generator state
     * @param blocking If true then add the interface for blocking service otherwise add async service interface
     * @return The generated service interface
     */
    private TypeSpec newServiceInterfaceSpec(final State state, final boolean blocking) {
        final ClassName serviceClass = blocking ? state.blockingServiceClass : state.serviceClass;
        final String name = serviceClass.simpleName();

        final TypeSpec.Builder interfaceSpecBuilder = interfaceBuilder(name)
                .addModifiers(PUBLIC)
                .addSuperinterface(ParameterizedTypeName.get(GrpcBindableService, state.serviceClass));

        state.serviceRpcInterfaces.stream()
                .filter(e -> e.blocking == blocking)
                .map(e -> e.className)
                .forEach(interfaceSpecBuilder::addSuperinterface);

        // Add the default bindService method.
        MethodSpec.Builder b = methodBuilder(bind + Service);
        if (printJavaDocs) {
            b.addJavadoc("Makes a {@link $T} bound to this instance implementing {@link $T}",
                    state.serviceFactoryClass, serviceClass);
        }
        interfaceSpecBuilder.addMethod(b
                .addAnnotation(Override.class)
                .addModifiers(PUBLIC)
                .addModifiers(DEFAULT)
                .returns(state.serviceFactoryClass)
                .addStatement("return new $T(this)", state.serviceFactoryClass)
                .build());

        // Add the default methodDescriptors method.
        interfaceSpecBuilder.addMethod(methodBuilder(methodDescriptors)
                .addAnnotation(Override.class)
                .addModifiers(PUBLIC)
                .addModifiers(DEFAULT)
                .returns(GrpcMethodDescriptorCollection)
                .addStatement("return $L", blocking ? BLOCKING_METHOD_DESCRIPTORS : ASYNC_METHOD_DESCRIPTORS)
                .build());

        // generate default service methods
        if (defaultServiceMethods) {
            final List<RpcInterface> interfaces = state.serviceRpcInterfaces.stream()
                    .filter(intf -> intf.blocking == blocking)
                    .collect(toList());
            for (final RpcInterface rpcInterface : interfaces) {
                final MethodDescriptorProto methodProto = rpcInterface.methodProto;
                final ClassName inClass = messageTypesMap.get(methodProto.getInputType());
                final ClassName outClass = messageTypesMap.get(methodProto.getOutputType());
                final String methodName = sanitizeIdentifier(methodProto.getName(), true);
                final String methodPath = context.methodPath(state.serviceProto, methodProto).substring(1);
                final MethodSpec methodSpec = newRpcMethodSpec(inClass, outClass, methodName,
                        methodProto.getClientStreaming(),
                        methodProto.getServerStreaming(),
                        !blocking ? EnumSet.of(INTERFACE) : (skipDeprecated ?
                                EnumSet.of(INTERFACE, BLOCKING, SERVER_RESPONSE) : EnumSet.of(INTERFACE, BLOCKING)),
                        false, (__, spec) -> {
                            spec.addAnnotation(Override.class);
                            spec.addModifiers(DEFAULT).addParameter(GrpcServiceContext, ctx);
                            final String errorMessage = "\"Method " + methodPath + " is unimplemented\"";
                            if (!blocking) {
                                final ClassName returnType = methodProto.getServerStreaming() ? Publisher : Single;
                                spec.addStatement("return $T.failed(new $T(new $T($T.UNIMPLEMENTED, $L)))",
                                        returnType, GrpcStatusException, GrpcStatus, GrpcStatusCode, errorMessage);
                            } else {
                                spec.addStatement("throw new $T(new $T($T.UNIMPLEMENTED, $L))",
                                        GrpcStatusException, GrpcStatus, GrpcStatusCode, errorMessage);
                            }
                            return spec;
                        });

                interfaceSpecBuilder.addMethod(methodSpec);
            }
        }

        return interfaceSpecBuilder.build();
    }

    private static String routeName(final MethodDescriptorProto methodProto) {
        return sanitizeIdentifier(methodProto.getName(), true);
    }

    private static ClassName routeInterfaceClass(final MethodDescriptorProto methodProto) {
        return methodProto.getClientStreaming() ?
                (methodProto.getServerStreaming() ? StreamingRoute : RequestStreamingRoute) :
                (methodProto.getServerStreaming() ? ResponseStreamingRoute : Route);
    }

    private static ClassName routeInterfaceClass(final MethodDescriptorProto methodProto, final boolean blocking) {
        return methodProto.getClientStreaming() ?
                (methodProto.getServerStreaming() ? blocking ? BlockingStreamingRoute : StreamingRoute :
                        blocking ? BlockingRequestStreamingRoute : RequestStreamingRoute) :
                (methodProto.getServerStreaming() ? blocking ? BlockingResponseStreamingRoute : ResponseStreamingRoute
                        : blocking ? BlockingRoute : Route);
    }

    private CodeBlock routeDefinition(final RpcInterface rpcInterface, final String routeName,
                                             final ClassName inClass, final ClassName outClass) {
        final ClassName routeInterfaceClass = routeInterfaceClass(rpcInterface.methodProto, rpcInterface.blocking);
        final TypeName routeType = ParameterizedTypeName.get(routeInterfaceClass, inClass, outClass);
        if (rpcInterface.blocking && rpcInterface.methodProto.getServerStreaming()) {
            final TypeSpec.Builder anonymousClassBuilder = anonymousClassBuilder("");

            final MethodSpec.Builder handleMethodSpecBuilder = methodBuilder(handle)
                    .addAnnotation(Override.class)
                    .addAnnotation(Deprecated.class)
                    .addModifiers(PUBLIC)
                    .addException(Exception.class)
                    .addParameter(GrpcServiceContext, ctx)
                    .addParameter(rpcInterface.methodProto.getClientStreaming() ?
                                    ParameterizedTypeName.get(BlockingIterable, inClass) :
                                    inClass,
                            request)
                    .addParameter(ParameterizedTypeName.get(GrpcPayloadWriter, outClass),
                            responseWriter);

            if (!skipDeprecated) {
                handleMethodSpecBuilder.addStatement("$L.$L($L, $L, $L)",
                        rpc, routeName, ctx, request, responseWriter);
            } else {
                handleMethodSpecBuilder.addStatement("throw new UnsupportedOperationException(\"" +
                        "This method is deprecated and should not be called by router or any generated code.\")");
            }

            anonymousClassBuilder.addMethod(handleMethodSpecBuilder.build());

            return CodeBlock.builder()
                    .addStatement("final $T $N = $L",
                            routeType, route, anonymousClassBuilder
                                    .addSuperinterface(routeType)
                                    .addMethod(methodBuilder(handle)
                                            .addAnnotation(Override.class)
                                            .addModifiers(PUBLIC)
                                            .addException(Exception.class)
                                            .addParameter(GrpcServiceContext, ctx)
                                            .addParameter(rpcInterface.methodProto.getClientStreaming() ?
                                                            ParameterizedTypeName.get(BlockingIterable, inClass) :
                                                            inClass,
                                                    request)
                                            .addParameter(ParameterizedTypeName.get(BlockingStreamingGrpcServerResponse,
                                                    outClass), response)
                                            .addStatement("$L.$L($L, $L, $L)",
                                                    rpc, routeName, ctx, request, response)
                                            .build())
                                    .addMethod(methodBuilder(close)
                                            .addAnnotation(Override.class)
                                            .addModifiers(PUBLIC)
                                            .addException(Exception.class)
                                            .addStatement("$L.$L()", rpc, close)
                                            .build())
                                    .addMethod(methodBuilder(closeGracefully)
                                            .addAnnotation(Override.class)
                                            .addModifiers(PUBLIC)
                                            .addException(Exception.class)
                                            .addStatement("$L.$L()", rpc, closeGracefully)
                                            .build())
                                    .build())
                    .build();
        }
        return CodeBlock.builder()
                .addStatement("final $T $N = $T.wrap($L::$L, $L)",
                        routeType, route, routeInterfaceClass, rpc, routeName, rpc)
                .build();
    }

    private static String routeFactoryMethodName(final MethodDescriptorProto methodProto) {
        return (methodProto.getClientStreaming() ?
                (methodProto.getServerStreaming() ? "streamingR" : "requestStreamingR") :
                (methodProto.getServerStreaming() ? "responseStreamingR" : "r")) +
                "outeFor";
    }

    private static String addRouteMethodName(final MethodDescriptorProto methodProto, final boolean blocking) {
        return "add" + (blocking ? Blocking : "") + streamingNameModifier(methodProto) + "Route";
    }

    private static String serviceFactoryBuilderInitChain(final ServiceDescriptorProto serviceProto,
                                                         final boolean blocking) {
        return serviceProto.getMethodList().stream()
                .map(methodProto -> routeName(methodProto) + (blocking ? Blocking : "") + '(' + service + ')')
                .collect(joining("."));
    }

    private static ClassName clientCallClass(final MethodDescriptorProto methodProto, final boolean blocking) {
        if (!blocking) {
            return methodProto.getClientStreaming() ?
                    (methodProto.getServerStreaming() ? StreamingClientCall : RequestStreamingClientCall) :
                    (methodProto.getServerStreaming() ? ResponseStreamingClientCall : ClientCall);
        }

        return methodProto.getClientStreaming() ?
                (methodProto.getServerStreaming() ? BlockingStreamingClientCall : BlockingRequestStreamingClientCall) :
                (methodProto.getServerStreaming() ? BlockingResponseStreamingClientCall : BlockingClientCall);
    }

    private static String newCallMethodName(final MethodDescriptorProto methodProto, final boolean blocking) {
        return "new" + (blocking ? Blocking : "") + streamingNameModifier(methodProto) + Call;
    }

    private static String streamingNameModifier(final MethodDescriptorProto methodProto) {
        return methodProto.getClientStreaming() ?
                (methodProto.getServerStreaming() ? "Streaming" : "RequestStreaming") :
                (methodProto.getServerStreaming() ? "ResponseStreaming" : "");
    }

    private static MethodSpec newDelegatingCompletableMethodSpec(final String methodName,
                                                                 final String fieldName) {
        return newDelegatingMethodSpec(methodName, fieldName, Completable, null);
    }

    private static MethodSpec newDelegatingMethodSpec(final String methodName,
                                                      final String fieldName,
                                                      @Nullable final ClassName returnClass,
                                                      @Nullable final ClassName thrownClass) {
        final MethodSpec.Builder methodSpecBuilder = methodBuilder(methodName)
                .addModifiers(PUBLIC)
                .addAnnotation(Override.class)
                .addStatement("$L$L.$L()", (returnClass != null ? "return " : ""), fieldName, methodName);

        if (returnClass != null) {
            methodSpecBuilder.returns(returnClass);
        }
        if (thrownClass != null) {
            methodSpecBuilder.addException(thrownClass);
        }

        return methodSpecBuilder.build();
    }

    private static MethodSpec newDelegatingCompletableToBlockingMethodSpec(final String blockingMethodName,
                                                                           final String completableMethodName,
                                                                           final String fieldName) {
        return methodBuilder(blockingMethodName)
                .addModifiers(PUBLIC)
                .addAnnotation(Override.class)
                .addException(Exception.class)
                .addStatement("$L.$L().toFuture().get()", fieldName, completableMethodName)
                .build();
    }
}
