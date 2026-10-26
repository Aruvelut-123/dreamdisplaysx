package com.dreamdisplays.platform.server

import io.github.arnodoelinger.platformweaver.PlatformOnly

/**
 * Marks a declaration shared between `Fabric`, `NeoForge` and `Forge` — the modded loaders that all
 * compile against vanilla / Mojang-mapped types (e.g. [net.minecraft.server.level.ServerPlayer]).
 * Stripped on `paper` and on any proxy target.
 */
@PlatformOnly("fabric", "neoforge", "forge")
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
@Retention(AnnotationRetention.SOURCE)
annotation class ModLoaderOnly
