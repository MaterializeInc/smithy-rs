/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.testutil

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.NullableIndex
import software.amazon.smithy.model.node.ObjectNode
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.implBlock
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProviderConfig
import software.amazon.smithy.rust.codegen.core.smithy.generators.StructureGenerator
import software.amazon.smithy.rust.codegen.core.testutil.TestModuleDocProvider
import software.amazon.smithy.rust.codegen.core.testutil.TestRuntimeConfig
import software.amazon.smithy.rust.codegen.server.smithy.RustServerCodegenPlugin
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenConfig
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.ServerModuleProvider
import software.amazon.smithy.rust.codegen.server.smithy.ServerRustSettings
import software.amazon.smithy.rust.codegen.server.smithy.ServerSymbolProviders
import software.amazon.smithy.rust.codegen.server.smithy.customizations.SmithyValidationExceptionConversionGenerator
import software.amazon.smithy.rust.codegen.server.smithy.customize.ServerCodegenDecorator
import software.amazon.smithy.rust.codegen.server.smithy.generators.ServerBuilderGenerator
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocol
import software.amazon.smithy.rust.codegen.server.smithy.protocols.ServerProtocolLoader

// These are the settings we default to if the user does not override them in their `smithy-build.json`.
val ServerTestRustSymbolProviderConfig = RustSymbolProviderConfig(
    runtimeConfig = TestRuntimeConfig,
    renameExceptions = false,
    nullabilityCheckMode = NullableIndex.CheckMode.SERVER,
    moduleProvider = ServerModuleProvider,
)

private fun testServiceShapeFor(model: Model) =
    model.serviceShapes.firstOrNull() ?: ServiceShape.builder().version("test").id("test#Service").build()

fun serverTestSymbolProvider(model: Model, serviceShape: ServiceShape? = null) =
    serverTestSymbolProviders(model, serviceShape).symbolProvider

private class ServerTestCodegenDecorator : ServerCodegenDecorator {
    override val name = "test"
    override val order: Byte = 0
}

fun serverTestSymbolProviders(
    model: Model,
    serviceShape: ServiceShape? = null,
    settings: ServerRustSettings? = null,
) =
    ServerSymbolProviders.from(
        serverTestRustSettings(),
        model,
        serviceShape ?: testServiceShapeFor(model),
        ServerTestRustSymbolProviderConfig,
        (
            settings ?: serverTestRustSettings(
                (serviceShape ?: testServiceShapeFor(model)).id,
            )
            ).codegenConfig.publicConstrainedTypes,
        ServerTestCodegenDecorator(),
        RustServerCodegenPlugin::baseSymbolProvider,
    )

fun serverTestRustSettings(
    service: ShapeId = ShapeId.from("notrelevant#notrelevant"),
    moduleName: String = "test-module",
    moduleVersion: String = "0.0.1",
    moduleAuthors: List<String> = listOf("notrelevant"),
    moduleDescription: String = "not relevant",
    moduleRepository: String? = null,
    runtimeConfig: RuntimeConfig = TestRuntimeConfig,
    codegenConfig: ServerCodegenConfig = ServerCodegenConfig(),
    license: String? = null,
    examplesUri: String? = null,
    customizationConfig: ObjectNode? = null,
) = ServerRustSettings(
    service,
    moduleName,
    moduleVersion,
    moduleAuthors,
    moduleDescription,
    moduleRepository,
    runtimeConfig,
    codegenConfig,
    license,
    examplesUri,
    customizationConfig,
)

fun serverTestCodegenContext(
    model: Model,
    serviceShape: ServiceShape? = null,
    settings: ServerRustSettings = serverTestRustSettings(),
    protocolShapeId: ShapeId? = null,
): ServerCodegenContext {
    val service = serviceShape ?: testServiceShapeFor(model)
    val protocol = protocolShapeId ?: ShapeId.from("test#Protocol")
    val serverSymbolProviders = ServerSymbolProviders.from(
        settings,
        model,
        service,
        ServerTestRustSymbolProviderConfig,
        settings.codegenConfig.publicConstrainedTypes,
        ServerTestCodegenDecorator(),
        RustServerCodegenPlugin::baseSymbolProvider,
    )

    return ServerCodegenContext(
        model,
        serverSymbolProviders.symbolProvider,
        TestModuleDocProvider,
        service,
        protocol,
        settings,
        serverSymbolProviders.unconstrainedShapeSymbolProvider,
        serverSymbolProviders.constrainedShapeSymbolProvider,
        serverSymbolProviders.constraintViolationSymbolProvider,
        serverSymbolProviders.pubCrateConstrainedShapeSymbolProvider,
    )
}

fun loadServerProtocol(model: Model): ServerProtocol {
    val codegenContext = serverTestCodegenContext(model)
    val (_, protocolGeneratorFactory) =
        ServerProtocolLoader(ServerProtocolLoader.DefaultProtocols).protocolFor(model, codegenContext.serviceShape)
    return protocolGeneratorFactory.buildProtocolGenerator(codegenContext).protocol
}

/**
 * In tests, we frequently need to generate a struct, a builder, and an impl block to access said builder.
 */
fun StructureShape.serverRenderWithModelBuilder(
    rustCrate: RustCrate,
    model: Model,
    symbolProvider: RustSymbolProvider,
    writer: RustWriter,
    protocol: ServerProtocol? = null,
) {
    StructureGenerator(model, symbolProvider, writer, this, emptyList()).render()
    val serverCodegenContext = serverTestCodegenContext(model)
    // Note that this always uses `ServerBuilderGenerator` and _not_ `ServerBuilderGeneratorWithoutPublicConstrainedTypes`,
    // regardless of the `publicConstrainedTypes` setting.
    val modelBuilder = ServerBuilderGenerator(
        serverCodegenContext,
        this,
        SmithyValidationExceptionConversionGenerator(serverCodegenContext),
        protocol ?: loadServerProtocol(model),
    )
    modelBuilder.render(rustCrate, writer)
    writer.implBlock(symbolProvider.toSymbol(this)) {
        modelBuilder.renderConvenienceMethod(this)
    }
}
